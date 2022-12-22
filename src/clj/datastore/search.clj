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

(defn- fetch-ids [ds q selected-context-id]
  (jdbc/execute!
   ds
   (sql/format
    (merge
     {:select   [:issues.id]
      :from     [:issues]
      :order-by [[:important :desc] [:updated_at :desc]]}
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
         result))))))

(defn- join-contexts [issue]
  (-> issue
      (dissoc :context_ids)
      (dissoc :context_titles)
      (assoc :contexts
             (zipmap (.getArray (:context_ids issue)) 
                     (.getArray (:context_titles issue))))))

(defn search-issues
  "Returns a sequence of items
   ([\"some-id\" {:title \"title\" :desc \"desc\"}])"
  [ds q selected-context-id]
  (prn "ds" q)
  (let [ids (map #(:issues/id %) (fetch-ids ds q selected-context-id)) 
        query
        (sql/format 
         {:select   [:issues.*
                     {:select :date
                      :from   [:events]
                      :where  [:= :events.issue_id :issues.id]}
                     [[:array_agg [:raw "contexts.id"]] :context_ids]
                     [[:array_agg [:raw "contexts.title"]] :context_titles]]
          :from     [:issues]
          :join     [:context_issue [:= :issues.id :context_issue.issue_id]
                     :contexts [:= :context_issue.context_id :contexts.id]]
          :where    [:in :issues.id [:inline ids]]
          :group-by [:issues.id]
          :order-by [[:issues.important :desc] [:issues.updated_at :desc]]})]
    (->> query
         (jdbc/execute! ds)
         (map un-namespace-keys)
         (map simplify-date)
         (map join-contexts))))
