(ns et.tr.db.category-rule
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]))

(def ^:private type->table
  {"person" :people
   "place" :places
   "project" :projects
   "goal" :goals})

(defn- load-rules [conn user-id]
  (jdbc/execute! conn
    (sql/format {:select [:id :source_type :source_id :target_type :target_id]
                 :from [:category_rules]
                 :where (db/user-id-where-clause user-id)
                 :order-by [[:id :asc]]})
    db/jdbc-opts))

(defn- rules-index [conn user-id]
  (reduce (fn [m {:keys [source_type source_id target_type target_id]}]
            (update m [source_type source_id] (fnil conj [])
                    [target_type target_id]))
          {}
          (load-rules conn user-id)))

(defn resolve-closure
  "Shared closure resolution used by both consumption sites (assignment and
  filter). Given a collection of seed [type id] pairs, returns the transitive
  closure as a vector of [type id] pairs (seeds included). Follows rule chains
  (A->B, B->C) and is cycle-safe (A->B, B->A does not loop)."
  [ds user-id seeds]
  (let [conn (db/get-conn ds)
        index (rules-index conn user-id)]
    (loop [visited []
           seen #{}
           queue (vec seeds)]
      (if (empty? queue)
        visited
        (let [node (first queue)
              rest-queue (subvec queue 1)]
          (if (contains? seen node)
            (recur visited seen rest-queue)
            (recur (conj visited node)
                   (conj seen node)
                   (into rest-queue (get index node [])))))))))

(defn apply-closure!
  "Insert every category in the closure into the given join table for the
  entity, skipping duplicates (no-op on already-assigned targets). Returns the
  closure as [{:category-type :category-id}...] so callers can surface the
  resulting categories in their response."
  [tx join-table entity-col entity-id closure]
  (doseq [[ct cid] closure]
    (jdbc/execute-one! tx
      (sql/format {:insert-into join-table
                   :values [{entity-col entity-id
                             :category_type ct
                             :category_id cid}]
                   :on-conflict []
                   :do-nothing true})))
  (mapv (fn [[ct cid]] {:category-type ct :category-id cid}) closure))

(defn- name-lookup [conn user-id]
  (let [user-where (db/user-id-where-clause user-id)]
    (reduce (fn [m [type table]]
              (reduce (fn [m {:keys [id name]}]
                        (assoc m [type id] name))
                      m
                      (jdbc/execute! conn
                        (sql/format {:select [:id :name]
                                     :from [table]
                                     :where user-where})
                        db/jdbc-opts)))
            {}
            type->table)))

(defn list-rules
  "List the user's rules as display rows enriched with the source/target
  category names, so the UI can render \"source -> target\" labels."
  [ds user-id]
  (let [conn (db/get-conn ds)
        lookup (name-lookup conn user-id)]
    (mapv (fn [{:keys [id source_type source_id target_type target_id]}]
            {:id id
             :source_type source_type
             :source_id source_id
             :source_name (get lookup [source_type source_id])
             :target_type target_type
             :target_id target_id
             :target_name (get lookup [target_type target_id])})
          (load-rules conn user-id))))

(defn add-rule [ds user-id source-type source-id target-type target-id]
  (db/validate-category-type! source-type)
  (db/validate-category-type! target-type)
  (when (and (not (and (= source-type target-type) (= source-id target-id)))
             (db/category-owned-by-user? ds source-type source-id user-id)
             (db/category-owned-by-user? ds target-type target-id user-id))
    (let [conn (db/get-conn ds)
          result (jdbc/execute-one! conn
                   (sql/format {:insert-into :category_rules
                                :values [{:user_id user-id
                                          :source_type source-type
                                          :source_id source-id
                                          :target_type target-type
                                          :target_id target-id}]
                                :on-conflict []
                                :do-nothing true
                                :returning [:id :source_type :source_id :target_type :target_id]})
                   db/jdbc-opts)]
      (tel/log! {:level :info :data {:user-id user-id :source [source-type source-id] :target [target-type target-id]}} "Category rule added")
      result)))

(defn delete-rule [ds user-id rule-id]
  (let [result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:delete-from :category_rules
                              :where [:and [:= :id rule-id] (db/user-id-where-clause user-id)]}))]
    {:success (pos? (:next.jdbc/update-count result))}))

(defn delete-rules-for-category
  "Remove every rule that references the given category as source or target.
  Called from delete-category so rules never dangle after a category is gone."
  [tx user-id category-type category-id]
  (jdbc/execute-one! tx
    (sql/format {:delete-from :category_rules
                 :where [:and
                         (db/user-id-where-clause user-id)
                         [:or
                          [:and [:= :source_type category-type] [:= :source_id category-id]]
                          [:and [:= :target_type category-type] [:= :target_id category-id]]]]})))
