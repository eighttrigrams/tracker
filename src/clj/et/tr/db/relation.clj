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
  (when (and (item-exists? ds user-id source-type source-id)
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

(defn get-relations-for-item [ds user-id source-type source-id]
  (validate-relation-type! source-type)
  (when (item-exists? ds user-id source-type source-id)
    (jdbc/execute! (db/get-conn ds)
      (sql/format {:select [:target_type :target_id]
                   :from [:relations]
                   :where [:and
                           [:= :source_type source-type]
                           [:= :source_id source-id]]})
      db/jdbc-opts)))

(defn- fetch-title-for-relation [conn type id]
  (let [table (case type "tsk" :tasks "res" :resources "met" :meets "jen" :journal_entries)]
    (:title (jdbc/execute-one! conn
              (sql/format {:select [:title]
                           :from [table]
                           :where [:= :id id]})
              db/jdbc-opts))))

(defn get-relations-with-titles [ds user-id source-type source-id]
  (when-let [relations (get-relations-for-item ds user-id source-type source-id)]
    (let [conn (db/get-conn ds)]
      (mapv (fn [{:keys [target_type target_id]}]
              {:type target_type
               :id target_id
               :title (fetch-title-for-relation conn target_type target_id)})
            relations))))

(defn- fetch-relations-batch [conn source-type source-ids]
  (when (seq source-ids)
    (jdbc/execute! conn
      (sql/format {:select [:source_id :target_type :target_id]
                   :from [:relations]
                   :where [:and
                           [:= :source_type source-type]
                           [:in :source_id source-ids]]})
      db/jdbc-opts)))

(defn- enrich-relations-with-titles [conn relations]
  (let [grouped (group-by :target_type relations)
        title-maps (into {}
                         (for [[type rels] grouped
                               :let [ids (mapv :target_id rels)
                                     table (case type "tsk" :tasks "res" :resources "met" :meets "jen" :journal_entries)
                                     items (jdbc/execute! conn
                                             (sql/format {:select [:id :title]
                                                          :from [table]
                                                          :where [:in :id ids]})
                                             db/jdbc-opts)]]
                           [type (into {} (map (juxt :id :title) items))]))]
    (mapv (fn [{:keys [target_type target_id] :as rel}]
            (assoc rel :title (get-in title-maps [target_type target_id])))
          relations)))

(defn associate-relations-with-items [items source-type conn]
  (let [item-ids (mapv :id items)
        relations (fetch-relations-batch conn source-type item-ids)
        enriched (enrich-relations-with-titles conn relations)
        relations-by-source (group-by :source_id enriched)]
    (mapv (fn [item]
            (let [item-relations (get relations-by-source (:id item) [])]
              (assoc item :relations
                     (mapv (fn [{:keys [target_type target_id title]}]
                             {:type target_type :id target_id :title title})
                           item-relations))))
          items)))

(defn delete-relations-for-item [conn source-type source-id]
  (jdbc/execute-one! conn
    (sql/format {:delete-from :relations
                 :where [:or
                         [:and [:= :source_type source-type] [:= :source_id source-id]]
                         [:and [:= :target_type source-type] [:= :target_id source-id]]]})))
