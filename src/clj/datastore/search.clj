(ns datastore.search
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.helpers
             :refer [un-namespace-keys simplify-date]]))

(defn- convert-q-to-query-string [q]
  (str/join " & " (map #(str % ":*") (str/split q #" "))))

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
                                 :where [:raw (format "searchable @@ to_tsquery('simple', '%s')" 
                                                      (convert-q-to-query-string q))]
                                 :order-by [[:important :desc] [:updated_at :desc]]})))
   (map un-namespace-keys)
   (map #(dissoc % :searchable))))

(defn- fetch-ids [ds q selected-context show-events?]
  (let [selected-context-id (:id selected-context)
        search-clause       (if (not= "" q)
                              [:raw (format "searchable @@ to_tsquery('simple', '%s')" 
                                            (convert-q-to-query-string q))] 
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

(defn- re-order [issues search-mode]
  (if (= 1 search-mode)
    (let [top (sort-by #(:short_title %) 
                       (filter #(and (some? (:short_title %))
                                     (= 0 (:short_title_ints %))) issues))
          bottom (sort-by #(:short_title_ints %)
                          (filter #(> (:short_title_ints %) 0) issues))]
      (concat top bottom))
    (let [top (reverse (sort-by #(:short_title_ints %)
                                (filter #(> (:short_title_ints %) 0) issues)))
          bottom (reverse (sort-by #(:short_title %)
                                   (filter #(and (= (:short_title_ints %) 0)
                                                 (some? (:short_title %))) issues)))]
      (concat top bottom))))

(defn- filter-by-selected-secondary-contexts [selected-secondary-contexts-ids 
                                              unassigned-secondary-contexts-selected?
                                              issues]
  (if (or unassigned-secondary-contexts-selected?
          (seq selected-secondary-contexts-ids))
    (filter
     (fn [issue]
       (or 
        (and unassigned-secondary-contexts-selected?
             (= 1 (count (:contexts issue))))
        (seq (set/intersection 
              (set (keys (:contexts issue)))
              selected-secondary-contexts-ids)))
       )issues)
    issues))

(defn search-issues
  "Returns a sequence of items
   ([\"some-id\" {:title \"title\" :desc \"desc\"}])"
  [db {:keys [q 
              selected-context 
              show-events? 
              selected-secondary-contexts-ids
              unassigned-secondary-contexts-selected?]
       :or   {q ""}}]
  
  (if-let [ids (seq (fetch-ids db q selected-context show-events?))]
    (->> ids
         (map #(:issues/id %))
         issues-query
         sql/format
         (jdbc/execute! db)
         (map un-namespace-keys)
         (map simplify-date)
         (map join-contexts)
         (map #(dissoc % :searchable))
         (#(if show-events? (sort-by :date %) %))
         (#(if (contains? #{1 2} (:search_mode selected-context))
             (re-order % (:search_mode selected-context))
             %))
         (filter-by-selected-secondary-contexts selected-secondary-contexts-ids
                                                unassigned-secondary-contexts-selected?))
    '()))
