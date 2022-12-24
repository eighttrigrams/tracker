(ns datastore.search
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.common :as common]
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

(defn- fetch-ids [ds q selected-context-id show-events?]
  (jdbc/execute!
   ds
   (sql/format
    (merge
     {:select   [:issues.id]
      :from     [:issues]
      :order-by [[:important :desc] [:updated_at :desc]]}
     (if show-events?
       {:where [:exists {:select [:events.id]
                         :from   [:events]
                         :where  [:and
                                  [:= :events.issue_id :issues.id]
                                  [:not= :events.archived [:inline true]]]}]}
       (if-not selected-context-id 
         (if (= "" q)
           {:where [:= :important [:inline true]]}
           {:where [:raw (format "searchable @@ to_tsquery('simple', '%s')" (str q ":*"))]})
         (if (= "" q)
           {:join  [:context_issue [:= :issues.id :context_issue.issue_id]]
            :where [:= :context_issue.context_id selected-context-id]}
           {:join  [:context_issue [:= :issues.id :context_issue.issue_id]]
            :where [:and
                    [:= :context_issue.context_id selected-context-id]
                    [:raw (format "searchable @@ to_tsquery('simple', '%s')" (str q ":*"))]]})))))))

(defn search-issues
  "Returns a sequence of items
   ([\"some-id\" {:title \"title\" :desc \"desc\"}])"
  [ds {:keys [q selected-context-id show-events?]
       :or   {q ""}}]
  (->> (fetch-ids ds q selected-context-id show-events?)
       (map #(:issues/id %))
       common/issues-query
       sql/format
       (jdbc/execute! ds)
       (map un-namespace-keys)
       (map simplify-date)
       (map common/join-contexts)
       (map #(dissoc % :searchable))
       (#(if show-events? (sort-by :date %) %))))
