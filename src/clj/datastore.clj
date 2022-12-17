(ns datastore
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [net.eighttrigrams.rocketoid.datastore.helpers
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

(defn insert-issue
  "Inserts an issue
   Examples:
   ```
   (insert-issue
    {:title \"title\"
     :description \"description\"})
   ```"
  [ds {title       :title
       description :description
       short_title :short_title ;; TODO rename to short-title in app
       tags        :tags}]
  (jdbc/execute! ds
                 (sql/format {:insert-into [:issues]
                              :columns [:inserted_at :updated_at :title :description :short_title :tags]
                              :values [[[:raw "NOW()"] [:raw "NOW()"] title description short_title tags]]})))

(defn get-issue-by-id [ds id]
  (un-namespace-keys
   (first (jdbc/execute! ds
                         (sql/format {:select :*
                                      :from [:issues]
                                      :where [:= :id [:inline id]]})))))

(defn update-issue
  "Updates an issue"
  [_id _issue]
  "TODO implement")

(defn insert-context
  [_context]
  "TODO implement")

(defn link-issue-to-context
  [_issue-id _context-id])
