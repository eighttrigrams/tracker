(ns datastore.search
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.helpers
             :refer [un-namespace-keys]]))

(defn search-issues
  "Returns a sequence of items
   ([\"some-id\" {:title \"title\" :desc \"desc\"}])"
  [ds q]
  #_(prn "ds" q)
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
    result))
