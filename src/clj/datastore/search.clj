(ns datastore.search
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.helpers
             :refer [un-namespace-keys simplify-date]]))

(defn search-contexts
  [ds q]
  (->>
   (if (= "" (or q ""))
     (jdbc/execute! ds
                    (sql/format {:select :*
                                 :from [:contexts]
                                 :order-by [[:important :desc] [:updated_at :desc]]}))
     (jdbc/execute! ds
                    (sql/format {:select :*
                                 :from   [:contexts]
                                 :where [:raw (format "searchable @@ to_tsquery('simple', '%s')" (str q ":*"))]
                                 :order-by [[:important :desc] [:updated_at :desc]]})))
   (map un-namespace-keys)
   (map #(dissoc % :searchable))))

(defn- fetch-ids [ds q selected-context show-events?]
  (let [selected-context-id (:id selected-context)
        search-clause       (if (not= "" q)
                              [:raw (format "searchable @@ to_tsquery('simple', '%s')" (str q ":*"))] 
                              [:=])
        join-clause         (if selected-context
                              [:context_issue [:= :issues.id :context_issue.issue_id]]
                              [])
        join-where-clause   (if selected-context
                              [:= :context_issue.context_id selected-context-id]
                              [:=])
        exists-clause       (if show-events? 
                              [:exists {:select [:events.id]
                                        :from   [:events]
                                        :where  [:and
                                                 [:= :events.issue_id :issues.id]
                                                 [:not= :events.archived [:inline true]]]}]
                              [:=])]
    (jdbc/execute!
     ds
     (sql/format
      {:select   [:issues.id]
       :from     [:issues]
       :order-by [[:important :desc] [:updated_at :desc]]
       :join     join-clause
       :where    [:and
                  exists-clause
                  join-where-clause
                  (if (and (= "" q) 
                           (not selected-context)
                           (not show-events?))
                    [:= :important [:inline true]] 
                    [:=])
                  search-clause]}))))

(defn- join-contexts [issue]
  (-> issue
      (dissoc :context_ids)
      (dissoc :context_titles)
      (assoc :contexts
             (zipmap (.getArray (:context_ids issue))
                     (.getArray (:context_titles issue))))))

(defn- issues-query [ids]
  {:select   [:issues.*
              {:select :date
               :from   [:events]
               :where  [:= :events.issue_id :issues.id]}
              [[:array_agg :contexts.id] :context_ids]
              [[:array_agg :contexts.title] :context_titles]]
   :from     [:issues]
   :join     [:context_issue [:= :issues.id :context_issue.issue_id]
              :contexts [:= :context_issue.context_id :contexts.id]]
   :where    [:in :issues.id [:inline ids]]
   :group-by [:issues.id]
   :order-by [[:issues.important :desc] [:issues.updated_at :desc]]})

(defn search-issues
  "Returns a sequence of items
   ([\"some-id\" {:title \"title\" :desc \"desc\"}])"
  [ds {:keys [q selected-context show-events?]
       :or   {q ""}}]
  (->> (fetch-ids ds q selected-context show-events?)
       (map #(:issues/id %))
       issues-query
       sql/format
       (jdbc/execute! ds)
       (map un-namespace-keys)
       (map simplify-date)
       (map join-contexts)
       (map #(dissoc % :searchable))
       (#(if show-events? (sort-by :date %) %))))
