(ns et.tr.db.category-exclusion
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.tr.db :as db]
            [et.tr.db.category-rule :as db.category-rule]))

(defn- seed-ids [conn user-id category-type names]
  (mapv :id (jdbc/execute! conn
              (sql/format {:select [:id]
                           :from [:categories]
                           :where [:and
                                   (db/user-id-where-clause user-id)
                                   (db/category-type-where category-type)
                                   [:in :name (vec names)]]})
              db/jdbc-opts)))

(defn- exclusion-clause [join-table entity-id-col entity-ref category-type category-ids]
  (when (seq category-ids)
    [:not [:exists {:select [1]
                    :from [join-table]
                    :where [:and
                            [:= (keyword (str (name join-table) "." (name entity-id-col))) (keyword (str (name entity-ref) ".id"))]
                            [:= (keyword (str (name join-table) ".category_type")) category-type]
                            [:in (keyword (str (name join-table) ".category_id")) category-ids]]}]]))

(defn build-exclusion-clauses
  "Turn {:people/:places/:workstreams/:projects/:goals/:assets [category-name...]}
  seeds into WHERE
  clauses hiding every entity that carries one of them. The seeds are expanded
  through the user's category rules first (transitively, cycle-safe), because
  rule closures are materialised directionally at categorize time: a task
  categorized only with a rule's target never carries its source, so excluding
  the source has to exclude the target too. Emits at most one NOT EXISTS per
  category type, ANDed by the caller — an entity survives only if it matches
  none, and an entity with no categories at all is never excluded. Unknown
  names simply resolve to nothing and exclude nothing."
  ([ds user-id excluded-categories]
   (build-exclusion-clauses ds user-id :task_categories :task_id :tasks excluded-categories))
  ([ds user-id join-table entity-id-col entity-ref excluded-categories]
   (let [conn (db/get-conn ds)
         seeds (vec (for [[seed-key names] excluded-categories
                          :when (seq names)
                          :let [category-type (db/category-key->type seed-key)]
                          :when category-type
                          id (seed-ids conn user-id category-type names)]
                      [category-type id]))]
     (if (empty? seeds)
       []
       (let [ids-by-type (reduce (fn [m [category-type id]]
                                   (update m category-type (fnil conj []) id))
                                 {}
                                 (db.category-rule/resolve-closure ds user-id seeds))]
         (filterv some?
                  (map #(exclusion-clause join-table entity-id-col entity-ref % (get ids-by-type %))
                       db/category-type-order)))))))
