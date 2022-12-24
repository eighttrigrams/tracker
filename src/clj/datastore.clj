(ns datastore
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.helpers
             :refer [un-namespace-keys simplify-date]]))

;; entity types
;; - issues
;; - context
;; - context-issue-relation
;; TODO
;; think about groups
;; normal contexts are folders
;; groups are superfolders
;; directed acyclycal graph

;; TODO move to search ns, explore varags here, make tut about varargs and destructuring?
;; TODO in minimals, show examples which use substitution/formatting

(defn new-issue [db value context-id]
  (let [issue
        (jdbc/execute-one! db
                           (sql/format {:insert-into [:issues]
                                        :columns     [:inserted_at :updated_at :title]
                                        :values      [[[:raw "NOW()"] [:raw "NOW()"] value]]})
                           
                           {:return-keys true})]
    (jdbc/execute! db
                   (sql/format {:insert-into [:context-issue]
                                :columns [:context_id :issue_id]
                                :values [[[:inline context-id]
                                          [:inline (:issues/id issue)]]]}))
    (-> issue
        un-namespace-keys
        (dissoc :searchable))))

(defn- delete-date [db issue-id]
  (jdbc/execute! db
                 (sql/format {:delete-from [:events]
                              :where [:= :issue_id [:inline issue-id]]})))

(defn- insert-date [db issue-id date]
  (jdbc/execute! db
                 (sql/format {:insert-into [:events]
                              :columns     [:issue_id :date :inserted_at :updated_at]
                              :values      [[[:inline issue-id]
                                             [:inline date]
                                             [:raw "NOW()"]
                                             [:raw "NOW()"]]]})))

(defn- update-issue* [db {:keys [id title short_title tags]}]
  (jdbc/execute-one! db
                     (sql/format {:update [:issues]
                                  :where  [:= :id [:inline id]]
                                  :set    {:title       [:inline title]
                                           :short_title [:inline short_title]
                                           :tags        [:inline tags]
                                           :updated_at  [:raw "NOW()"]}})
                     {:return-keys true}))

(defn- join-related-issues [issue]
  (-> issue
      (dissoc :related_issues_ids)
      (dissoc :related_issues_titles)
      (assoc :related_issues
             (zipmap (.getArray (:related_issues_ids issue))
                     (.getArray (:related_issues_titles issue))))))

(defn- issues-query [id]
  {:select   [:issues.*
              {:select :date
               :from   [:events]
               :where  [:= :events.issue_id :issues.id]}
              [[:array_agg :related_issues.id] :related_issues_ids]
              [[:array_agg :related_issues.title] :related_issues_titles]]
   :from     [:issues]
   :join     [:issue_issue [:= :issues.id :issue_issue.left_id]
              [:issues :related_issues] [:= :related_issues.id :issue_issue.right_id]]
   :where    [:= :issues.id [:inline id]]
   :group-by [:issues.id]
   :order-by [[:issues.important :desc] [:issues.updated_at :desc]]})

(defn- simple-issues-query [id]
  {:select   [:issues.*
              {:select :date
               :from   [:events]
               :where  [:= :events.issue_id :issues.id]}]
   :from     [:issues]
   :where    [:= :issues.id [:inline id]]
   :group-by [:issues.id]
   :order-by [[:issues.important :desc] [:issues.updated_at :desc]]})

(defn- get-issue-with-related-issues [db id]
  (when-let [result (-> id
                        issues-query
                        sql/format
                        (#(jdbc/execute-one! db % {:return-keys true})) ;; TODO try swiss-arrows or extract fn
                        )]
    (join-related-issues result)))

(defn get-issue [db {:keys [id]}]
  (-> (if-let [issue (get-issue-with-related-issues db id)]
        issue
        (-> id
            simple-issues-query
            sql/format
            (#(jdbc/execute-one! db % {:return-keys true}))))
      un-namespace-keys
      simplify-date
      (dissoc :searchable)))

(defn update-issue [db {:keys [id date] :as issue}]
  (delete-date db id)
  (update-issue* db issue)
  (when date
    (insert-date db id date))
  (get-issue db issue))

(defn update-issue-description [db {:keys [id description] :as issue}]
  (jdbc/execute-one! db
                     (sql/format {:update [:issues]
                                  :set    {:description [:inline description]
                                           :updated_at [:raw "NOW()"]}
                                  :where  [:= :id [:inline id]]}))
  (get-issue db issue))

(defn update-context-description [ds {:keys [id description]}]
  (-> 
   (jdbc/execute-one! ds
                      (sql/format {:update [:contexts]
                                   :set    {:description [:inline description]
                                            :updated_at  [:raw "NOW()"]}
                                   :where  [:= :id [:inline id]]})
                      {:return-keys true})
   un-namespace-keys
   (dissoc :searchable)))