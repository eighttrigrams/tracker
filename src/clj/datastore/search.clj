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
                                                  :order-by [[:updated_at :desc]]}))
                      (jdbc/execute! ds
                                     (sql/format {:select :*
                                                  :from   [:contexts]
                                                  :where [:raw (format "searchable @@ to_tsquery('simple', '%s')" (str q ":*"))]
                                                  :order-by [[:updated_at :desc]]}))))]
    #_(prn result)
    result))

(defn search-issues
  "Returns a sequence of items
   ([\"some-id\" {:title \"title\" :desc \"desc\"}])"
  [ds q selected-context-id]
  (prn "ds" q)
  (map 
   un-namespace-keys
   (jdbc/execute! 
    ds
    (sql/format 
     (merge 
      {:from     [:issues]
       :order-by [[:updated_at :desc]]
       :limit    100}
      (if-not selected-context-id
        (let [result
              (if (= "" q)
                {:select :*
                 :where    [:= :important [:inline true]]
                 }
                {:select :*
                 :where  [:raw (format "searchable @@ to_tsquery('simple', '%s')" (str q ":*"))]})]
          #_(prn result)
          result)
        (let [result
              (if (= "" q)
                {:select :*
                 :join   [:context_issue [:= :issues.id :context_issue.issue_id]]
                 :where  [:= :context_issue.context_id selected-context-id]}
                {:select :*
                 :join   [:context_issue [:= :issues.id :context_issue.issue_id]]
                 :where  [:and
                          [:= :context_issue.context_id selected-context-id]
                          [:raw (format "searchable @@ to_tsquery('simple', '%s')" (str q ":*"))]]})]
          #_(prn result)
          result)))))))
