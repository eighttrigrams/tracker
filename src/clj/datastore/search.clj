(ns datastore.search
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.helpers
             :refer [un-namespace-keys simplify-date]]))

(defn search-contexts
  [ds q]
  (let [result (map un-namespace-keys
                    (if (= "" q)
                      (jdbc/execute! ds
                                     (sql/format {:select :*
                                                  :from [:contexts]
                                                  :order-by [[:important :desc] [:updated_at :desc]]}))
                      (jdbc/execute! ds
                                     (sql/format {:select :*
                                                  :from   [:contexts]
                                                  :where [:raw (format "searchable @@ to_tsquery('simple', '%s')" (str q ":*"))]
                                                  :order-by [[:important :desc] [:updated_at :desc]]}))))]
    #_(prn result)
    result))

(defn- fetch-contexts [ds]
  (fn [m]
    (assoc m :contexts 
           (map un-namespace-keys (jdbc/execute! ds 
                                                 ["select contexts.id, contexts.title from contexts join context_issue on context_issue.context_id = contexts.id where context_issue.issue_id = ?"
                                                  (:id m)])))))

(defn search-issues
  "Returns a sequence of items
   ([\"some-id\" {:title \"title\" :desc \"desc\"}])"
  [ds q selected-context-id]
  (prn "ds" q)
  (->>
   (jdbc/execute! 
    ds
    (sql/format 
     (merge 
      {:select   [:*
                  {:select :date
                   :from   [:events]
                   :where  [:= :events.issue_id :issues.id]}]
       :from     [:issues]
       :order-by [[:important :desc] [:updated_at :desc]]
       :limit    100}
      (if-not selected-context-id
        (let [result
              (if (= "" q)
                {:where [:= :important [:inline true]]}
                {:where [:raw (format "searchable @@ to_tsquery('simple', '%s')" (str q ":*"))]})]
          result)
        (let [result
              (if (= "" q)
                {:join  [:context_issue [:= :issues.id :context_issue.issue_id]]
                 :where [:= :context_issue.context_id selected-context-id]}
                {:join  [:context_issue [:= :issues.id :context_issue.issue_id]]
                 :where [:and
                         [:= :context_issue.context_id selected-context-id]
                         [:raw (format "searchable @@ to_tsquery('simple', '%s')" (str q ":*"))]]})]
          #_(prn result)
          result)))))
   (map un-namespace-keys)
   (map simplify-date)
   (map (fetch-contexts ds))))
