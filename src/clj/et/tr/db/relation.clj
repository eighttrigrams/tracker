(ns et.tr.db.relation
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]))

(def ^:private valid-relation-types #{"tsk" "res" "met" "jen"})

(defn- validate-relation-type! [type]
  (when-not (contains? valid-relation-types type)
    (throw (ex-info "Invalid relation type" {:type type}))))

(defn- item-exists? [ds user-id type id]
  (let [conn (db/get-conn ds)
        table (case type "tsk" :tasks "res" :resources "met" :meets "jen" :journal_entries)
        user-where (db/user-id-where-clause user-id)]
    (some? (jdbc/execute-one! conn
             (sql/format {:select [:id]
                          :from [table]
                          :where [:and [:= :id id] user-where]})
             db/jdbc-opts))))

(defn add-relation [ds user-id source-type source-id target-type target-id]
  (validate-relation-type! source-type)
  (validate-relation-type! target-type)
  (when (and (not (and (= source-type target-type) (= source-id target-id)))
             (item-exists? ds user-id source-type source-id)
             (item-exists? ds user-id target-type target-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:insert-into :relations
                       :values [{:source_type source-type
                                 :source_id source-id
                                 :target_type target-type
                                 :target_id target-id}]
                       :on-conflict []
                       :do-nothing true}))
        (jdbc/execute-one! tx
          (sql/format {:insert-into :relations
                       :values [{:source_type target-type
                                 :source_id target-id
                                 :target_type source-type
                                 :target_id source-id}]
                       :on-conflict []
                       :do-nothing true})))
      (tel/log! {:level :info :data {:source-type source-type :source-id source-id
                                      :target-type target-type :target-id target-id
                                      :user-id user-id}} "Relation added")
      {:success true})))

(defn delete-relation [ds user-id source-type source-id target-type target-id]
  (validate-relation-type! source-type)
  (validate-relation-type! target-type)
  (when (and (item-exists? ds user-id source-type source-id)
             (item-exists? ds user-id target-type target-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :relations
                       :where [:and
                               [:= :source_type source-type]
                               [:= :source_id source-id]
                               [:= :target_type target-type]
                               [:= :target_id target-id]]}))
        (jdbc/execute-one! tx
          (sql/format {:delete-from :relations
                       :where [:and
                               [:= :source_type target-type]
                               [:= :source_id target-id]
                               [:= :target_type source-type]
                               [:= :target_id source-id]]})))
      (tel/log! {:level :info :data {:source-type source-type :source-id source-id
                                      :target-type target-type :target-id target-id
                                      :user-id user-id}} "Relation deleted")
      {:success true})))

(defn- fetch-bidirectional-relations [conn item-type item-id]
  (let [as-source (jdbc/execute! conn
                    (sql/format {:select [:target_type :target_id]
                                 :from [:relations]
                                 :where [:and
                                         [:= :source_type item-type]
                                         [:= :source_id item-id]]})
                    db/jdbc-opts)
        as-target (jdbc/execute! conn
                    (sql/format {:select [[:source_type :target_type] [:source_id :target_id]]
                                 :from [:relations]
                                 :where [:and
                                         [:= :target_type item-type]
                                         [:= :target_id item-id]]})
                    db/jdbc-opts)]
    (vec (distinct (concat as-source as-target)))))

(defn get-relations-for-item [ds user-id item-type item-id]
  (validate-relation-type! item-type)
  (when (item-exists? ds user-id item-type item-id)
    (fetch-bidirectional-relations (db/get-conn ds) item-type item-id)))

(defn fetch-title-for-relation [conn type id]
  (let [table (case type "tsk" :tasks "res" :resources "met" :meets "jen" :journal_entries)
        select (cond-> [:title :relation_badge_title]
                 (= type "tsk") (conj :done)
                 (= type "met") (conj :start_date))
        row (jdbc/execute-one! conn
              (sql/format {:select select
                           :from [table]
                           :where [:= :id id]})
              db/jdbc-opts)]
    (cond-> {:title (:title row)
             :badge_title (:relation_badge_title row)}
      (= type "tsk") (assoc :done (:done row))
      (= type "met") (assoc :start_date (:start_date row)))))

(defn get-relations-with-titles [ds user-id source-type source-id]
  (when-let [relations (get-relations-for-item ds user-id source-type source-id)]
    (let [conn (db/get-conn ds)]
      (mapv (fn [{:keys [target_type target_id]}]
              (let [{:keys [title badge_title done start_date]} (fetch-title-for-relation conn target_type target_id)]
                (cond-> {:type target_type
                         :id target_id
                         :title title
                         :badge_title badge_title}
                  (= target_type "tsk") (assoc :done done)
                  (= target_type "met") (assoc :start_date start_date))))
            relations))))

(defn- fetch-relations-batch [conn item-type item-ids]
  (when (seq item-ids)
    (let [as-source (jdbc/execute! conn
                      (sql/format {:select [[:source_id :item_id] :target_type :target_id]
                                   :from [:relations]
                                   :where [:and
                                           [:= :source_type item-type]
                                           [:in :source_id item-ids]]})
                      db/jdbc-opts)
          as-target (jdbc/execute! conn
                      (sql/format {:select [[:target_id :item_id] [:source_type :target_type] [:source_id :target_id]]
                                   :from [:relations]
                                   :where [:and
                                           [:= :target_type item-type]
                                           [:in :target_id item-ids]]})
                      db/jdbc-opts)]
      (vec (distinct (concat as-source as-target))))))

(defn- enrich-relations-with-titles [conn relations]
  (let [grouped (group-by :target_type relations)
        title-maps (into {}
                         (for [[type rels] grouped
                               :let [ids (mapv :target_id rels)
                                     table (case type "tsk" :tasks "res" :resources "met" :meets "jen" :journal_entries)
                                     select (cond-> [:id :title :relation_badge_title]
                                              (= type "tsk") (conj :done)
                                              (= type "met") (conj :start_date))
                                     keep-keys (cond-> [:title :relation_badge_title]
                                                 (= type "tsk") (conj :done)
                                                 (= type "met") (conj :start_date))
                                     items (jdbc/execute! conn
                                             (sql/format {:select select
                                                          :from [table]
                                                          :where [:in :id ids]})
                                             db/jdbc-opts)]]
                           [type (into {} (map (juxt :id #(select-keys % keep-keys)) items))]))]
    (mapv (fn [{:keys [target_type target_id] :as rel}]
            (let [{:keys [title relation_badge_title done start_date]} (get-in title-maps [target_type target_id])]
              (cond-> (assoc rel :title title :badge_title relation_badge_title)
                (= target_type "tsk") (assoc :done done)
                (= target_type "met") (assoc :start_date start_date))))
          relations)))

(defn associate-relations-with-items [items item-type conn]
  (let [item-ids (mapv :id items)
        relations (fetch-relations-batch conn item-type item-ids)
        enriched (enrich-relations-with-titles conn relations)
        relations-by-item (group-by :item_id enriched)]
    (mapv (fn [item]
            (let [item-relations (get relations-by-item (:id item) [])]
              (assoc item :relations
                     (mapv (fn [{:keys [target_type target_id title badge_title done start_date]}]
                             (cond-> {:type target_type :id target_id :title title :badge_title badge_title}
                               (= target_type "tsk") (assoc :done done)
                               (= target_type "met") (assoc :start_date start_date)))
                           item-relations))))
          items)))

(defn delete-relations-for-item [conn source-type source-id]
  (jdbc/execute-one! conn
    (sql/format {:delete-from :relations
                 :where [:or
                         [:and [:= :source_type source-type] [:= :source_id source-id]]
                         [:and [:= :target_type source-type] [:= :target_id source-id]]]})))
