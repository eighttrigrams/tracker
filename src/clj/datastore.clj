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

(defn new-issue [db {title :title} context-id]
  (tap> [:insert-issue context-id title])
  (let [issue
        (jdbc/execute-one! db
                           (sql/format {:insert-into [:issues]
                                        :columns     [:inserted_at :updated_at :title]
                                        :values      [[[:raw "NOW()"] [:raw "NOW()"] title]]})
                           
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

(defn- update-context* [db {:keys [id title short_title tags]}]
  (jdbc/execute-one! db
                     (sql/format {:update [:contexts]
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

(defn- join-contexts [issue] ;; TODO dedup with search/join-contexts
  (-> issue
      (dissoc :context_ids)
      (dissoc :context_titles)
      (assoc :contexts
             (zipmap (.getArray (:context_ids issue))
                     (.getArray (:context_titles issue))))))

(defn- issues-query [id]
  {:select   [:issues.*
              {:select :date
               :from   [:events]
               :where  [:= :events.issue_id :issues.id]}
              [[:array_agg :related_issues.id] :related_issues_ids]
              [[:array_agg :related_issues.title] :related_issues_titles]
              [[:array_agg :contexts.id] :context_ids]
              [[:array_agg :contexts.title] :context_titles]]
   :from     [:issues]
   :join     [:issue_issue [:= :issues.id :issue_issue.left_id]
              [:issues :related_issues] [:= :related_issues.id :issue_issue.right_id]
              :context_issue [:= :issues.id :context_issue.issue_id]
              :contexts [:= :context_issue.context_id :contexts.id]]
   :where    [:= :issues.id [:inline id]]
   :group-by [:issues.id]
   :order-by [[:issues.important :desc] [:issues.updated_at :desc]]}) ;; TODO remove

(defn- simple-issues-query [id]
  {:select   [:issues.*
              {:select :date
               :from   [:events]
               :where  [:= :events.issue_id :issues.id]}
              [[:array_agg :contexts.id] :context_ids]
              [[:array_agg :contexts.title] :context_titles]]
   :from     [:issues]
   :join     [:context_issue [:= :issues.id :context_issue.issue_id]
              :contexts [:= :context_issue.context_id :contexts.id]]
   :where    [:= :issues.id [:inline id]]
   :group-by [:issues.id] ;; TODO remove
   :order-by [[:issues.important :desc] [:issues.updated_at :desc]]}) ;; TODO remove

(defn- get-issue-with-related-issues [db id]
  (when-let [result (-> id
                        issues-query
                        sql/format
                        (#(jdbc/execute-one! db % {:return-keys true}))
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
      join-contexts
      simplify-date
      (dissoc :searchable)))

(defn- join-secondary-contexts [context]
  (-> context
      (dissoc :secondary_contexts_ids)
      (dissoc :secondary_contexts_titles)
      (assoc :secondary_contexts
             (zipmap (.getArray (:secondary_contexts_ids context))
                     (.getArray (:secondary_contexts_titles context))))))

(defn- simple-contexts-query [id]
  {:select   [:contexts.*]
   :from     [:contexts]
   :where    [:= :contexts.id [:inline id]]})

(defn- contexts-query [id]
  {:select   [:contexts.*
              [[:array_agg :secondary_contexts.id] :secondary_contexts_ids]
              [[:array_agg :secondary_contexts.title] :secondary_contexts_titles]]
   :from     [:contexts]
   :join     [:context_context [:= :contexts.id :context_context.parent_id]
              [:contexts :secondary_contexts] [:= :secondary_contexts.id :context_context.child_id]]
   :where    [:= :contexts.id [:inline id]]
   :group-by [:contexts.id]})

(defn- get-context-with-secondary-contexts [db id]
  (when-let [result (-> id
                        contexts-query
                        sql/format
                        (#(jdbc/execute-one! db % {:return-keys true}))
                        )]
    (join-secondary-contexts result)))

(defn get-context [db {:keys [id]}]
  (-> (if-let [context (get-context-with-secondary-contexts db id)]
        context
        (-> id
            simple-contexts-query
            sql/format
            (#(jdbc/execute-one! db % {:return-keys true}))))
      un-namespace-keys
      (dissoc :searchable)))

(defn update-issue [db {:keys [id date] :as issue}]
  (delete-date db id)
  (update-issue* db issue)
  (when date
    (insert-date db id date))
  (get-issue db issue))

(defn update-context [db context] 
  (update-context* db context)
  (get-context db context))

(defn update-issue-description [db {:keys [id description] :as issue}]
  (jdbc/execute-one! db
                     (sql/format {:update [:issues]
                                  :set    {:description [:inline description]
                                           :updated_at [:raw "NOW()"]}
                                  :where  [:= :id [:inline id]]}))
  (get-issue db issue))

(defn update-context-description [db {:keys [id description]}]
  (-> 
   (jdbc/execute-one! db
                      (sql/format {:update [:contexts]
                                   :set    {:description [:inline description]
                                            :updated_at  [:raw "NOW()"]}
                                   :where  [:= :id [:inline id]]})
                      {:return-keys true})
   un-namespace-keys
   (dissoc :searchable)))

(defn cycle-search-mode [db {:keys [id] :as context}]
  (let [context (get-context db context)]
    (jdbc/execute-one! db (sql/format {:update [:contexts]
                                       :set    {:search_mode [:inline (mod (inc (:search_mode context)) 3)]}
                                       :where  [:= :id [:inline id]]}))
    (get-context db context)))

(defn delete-issue [db {:keys [id]}]
  (jdbc/execute! db (sql/format {:delete-from [:context_issue]
                                 :where [:= :issue_id [:inline id]]}))
  (jdbc/execute! db (sql/format {:delete-from [:issue_issue]
                                 :where [:or 
                                         [:= :left_id [:inline id]]
                                         [:= :right_id [:inline id]]]}))
  (jdbc/execute! db (sql/format {:delete-from [:issues]
                                 :where [:= :id [:inline id]]})))

(defn reprioritize-issue [db {:keys [id]}]
  (jdbc/execute! db (sql/format {:update [:issues]
                                 :set {:updated_at [:raw "NOW()"]}
                                 :where [:= :id [:inline id]]})))

(defn mark-issue-important [db {:keys [id important] :as selected-issue}]
  (jdbc/execute! db (sql/format {:update [:issues]
                                 :set {:important (not important)}
                                 :where [:= :id [:inline id]]}))
  (get-issue db selected-issue))

(defn link-issue-contexts [db selected-issue link-issue-contexts]
  (jdbc/execute! db (sql/format {:delete-from [:context_issue]
                                 :where [:= :issue_id [:inline (:id selected-issue)]]}))
  (doall (for [context-id link-issue-contexts]
           (jdbc/execute! db (sql/format {:insert-into [:context_issue]
                                          :columns [:issue_id :context_id]
                                          :values [[[:inline (:id selected-issue)] 
                                                    [:inline context-id]]]}))))
  (get-issue db selected-issue))
