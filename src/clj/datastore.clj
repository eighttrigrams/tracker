(ns datastore
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.helpers
             :refer [un-namespace-keys]]))

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
  (let [issue (un-namespace-keys 
               (jdbc/execute-one! db
                                  (sql/format {:insert-into [:issues]
                                               :columns     [:inserted_at :updated_at :title]
                                               :values      [[[:raw "NOW()"] [:raw "NOW()"] value]]})
                                  
                                  {:return-keys true}))]
    (jdbc/execute! db
                   (sql/format {:insert-into [:context-issue]
                                :columns [:context_id :issue_id]
                                :values [[[:inline context-id]
                                          [:inline (:id issue)]]]}))
    issue))

(defn update-issue [db id value]
  (un-namespace-keys
   (jdbc/execute-one! db
                      (sql/format {:update [:issues]
                                   :where  [:= :id [:inline id]]
                                   :set    {:title       [:inline (:title value)]
                                            :short_title [:inline (:short_title value)]
                                            :tags        [:inline (:tags value)]
                                            :updated_at  [:raw "NOW()"]}})
                      {:return-keys true})))

(defn update-issue-description [ds id value]
  (jdbc/execute! ds
                 (sql/format {:update [:issues]
                              :set    {:description [:inline value]
                                       :updated_at [:raw "NOW()"]}
                              :where  [:= :id [:inline id]]}))
  ;; TODO dedup, see above
  (un-namespace-keys
   (first (jdbc/execute! ds
                         (sql/format {:select :*
                                      :from [:issues]
                                      :where [:= :id [:inline id]]})))))

;; TODO dedup, see above
(defn update-context-description [ds id value]
  (jdbc/execute! ds
                 (sql/format {:update [:contexts]
                              :set    {:description [:inline value]
                                       :updated_at [:raw "NOW()"]}
                              :where  [:= :id [:inline id]]}))
  ;; TODO dedup, see above
  (un-namespace-keys
   (first (jdbc/execute! ds
                         (sql/format {:select :*
                                      :from [:contexts]
                                      :where [:= :id [:inline id]]})))))