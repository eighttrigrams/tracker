(ns datastore
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.common :as common]
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

(defn update-issue [db {:keys [id date] :as issue}]
  (delete-date db id)
  (update-issue* db issue)
  (when date
    (insert-date db id date))
  (-> id
      list
      common/issues-query
      sql/format
      (#(jdbc/execute-one! db % {:return-keys true})) ;; TODO try swiss-arrows or extract fn
      un-namespace-keys
      common/join-contexts
      simplify-date
      (dissoc :searchable)))

;; TODO dedup, see above
(defn get-issue [db {:keys [id]}]
  (tap> [:get-issue id])
  (-> id
      list
      common/issues-query
      sql/format
      (#(jdbc/execute-one! db % {:return-keys true}))
      un-namespace-keys
      common/join-contexts
      simplify-date
      (dissoc :searchable)))

(defn update-issue-description [ds id value]
  (-> 
   (jdbc/execute-one! ds
                      (sql/format {:update [:issues]
                                   :set    {:description [:inline value]
                                            :updated_at [:raw "NOW()"]}
                                   :where  [:= :id [:inline id]]})
                      {:return-keys true})
   un-namespace-keys
   (dissoc :searchable)))

;; TODO dedup, see above
(defn update-context-description [ds id value]
  (-> 
   (jdbc/execute-one! ds
                      (sql/format {:update [:contexts]
                                   :set    {:description [:inline value]
                                            :updated_at  [:raw "NOW()"]}
                                   :where  [:= :id [:inline id]]})
                      {:return-keys true})
   un-namespace-keys
   (dissoc :searchable)))