(ns datastore.search
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.helpers
             :refer [un-namespace-keys]]))

(defn search-contexts
  [ds q]
  (let [result (map un-namespace-keys
                    (if (= "" q)
                      (jdbc/execute! ds
                                     (sql/format {:select :*
                                                  :from [:contexts]
                                                  :limit 15}))
                      (jdbc/execute! ds
                                     (sql/format {:select :*
                                                  :from   [:contexts]
                                                  :where [:raw (format "searchable @@ to_tsquery('simple', '%s')" (str q ":*"))]
                                                  :limit  15}))))]
    #_(prn result)
    result))

(defn search-issues
  "Returns a sequence of items
   ([\"some-id\" {:title \"title\" :desc \"desc\"}])"
  [ds q selected-context-id]
  (prn "ds" q)
  (if-not selected-context-id
    (let [result (map un-namespace-keys
                      (if (= "" q)
                        (jdbc/execute! ds
                                       (sql/format {:select :*
                                                    :from [:issues]
                                                    :limit 15}))
                        (jdbc/execute! ds
                                       (sql/format {:select :*
                                                    :from   [:issues]
                                                    :where [:raw (format "searchable @@ to_tsquery('simple', '%s')" (str q ":*"))]
                                                    :limit  15}))))]
      #_(prn result)
      result)
    (let [result (map un-namespace-keys
                      (if (= "" q)
                        (jdbc/execute! ds
                                       (sql/format {:select :*
                                                    :from [:issues]
                                                    :join [:context_issue [:= :issues.id :context_issue.issue_id]]
                                                    :where [:= :context_issue.context_id selected-context-id]
                                                    :limit 15}))
                        (jdbc/execute! ds
                                       (sql/format {:select :*
                                                    :from   [:issues]
                                                    :join [:context_issue [:= :issues.id :context_issue.issue_id]]
                                                    :where [:and
                                                            [:= :context_issue.context_id selected-context-id]
                                                            [:raw (format "searchable @@ to_tsquery('simple', '%s')" (str q ":*"))]]
                                                    :limit  15}))))]
      #_(prn result)
      result)))
