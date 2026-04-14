(ns et.tr.db.journal-entry
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]
            [et.tr.db.relation :as relation]))

(defn- build-journal-entry-category-clauses [categories]
  (let [people-clause (db/build-category-subquery :journal_entry_categories :journal_entry_id :journal_entries "person" (:people categories))
        places-clause (db/build-category-subquery :journal_entry_categories :journal_entry_id :journal_entries "place" (:places categories))
        projects-clause (db/build-category-subquery :journal_entry_categories :journal_entry_id :journal_entries "project" (:projects categories))
        goals-clause (db/build-category-subquery :journal_entry_categories :journal_entry_id :journal_entries "goal" (:goals categories))]
    (filterv some? [people-clause places-clause projects-clause goals-clause])))

(defn- associate-categories-with-journal-entries [entries categories-by-entry people-by-id places-by-id projects-by-id goals-by-id]
  (mapv (fn [entry]
          (let [entry-categories (get categories-by-entry (:id entry) [])]
            (assoc entry
                   :people (db/extract-category entry-categories "person" people-by-id)
                   :places (db/extract-category entry-categories "place" places-by-id)
                   :projects (db/extract-category entry-categories "project" projects-by-id)
                   :goals (db/extract-category entry-categories "goal" goals-by-id))))
        entries))

(defn list-journal-entries
  ([ds user-id] (list-journal-entries ds user-id {}))
  ([ds user-id opts]
   (let [{:keys [search-term importance context strict categories sort-mode journal-id]} opts
         conn (db/get-conn ds)
         user-where (db/user-id-where-clause user-id)
         search-clause (db/build-search-clause search-term [:title :tags])
         importance-clause (db/build-importance-clause importance)
         scope-clause (db/build-scope-clause context strict)
         journal-clause (when journal-id [:= :journal_id journal-id])
         category-clauses (build-journal-entry-category-clauses categories)
         where-clause (into [:and user-where]
                            (concat (filter some? [search-clause importance-clause scope-clause journal-clause])
                                    category-clauses))
         entries (jdbc/execute! conn
                   (sql/format {:select db/journal-entry-select-columns
                                :from [:journal_entries]
                                :where where-clause
                                :order-by (case sort-mode
                                            "added" [[:created_at :desc]]
                                            [[:sort_order :asc]])})
                   db/jdbc-opts)
         entry-ids (mapv :id entries)
         categories-data (when (seq entry-ids)
                           (jdbc/execute! conn
                             (sql/format {:select [:journal_entry_id :category_type :category_id]
                                          :from [:journal_entry_categories]
                                          :where [:in :journal_entry_id entry-ids]})
                             db/jdbc-opts))
         {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
         categories-by-entry (group-by :journal_entry_id categories-data)]
     (-> (associate-categories-with-journal-entries entries categories-by-entry people-by-id places-by-id projects-by-id goals-by-id)
         (relation/associate-relations-with-items "jen" conn)))))

(defn list-today-journal-entries
  [ds user-id opts]
  (let [{:keys [context strict]} opts
        conn (db/get-conn ds)
        user-where (db/user-id-where-clause user-id)
        scope-clause (db/build-scope-clause context strict)
        today-expr [:raw "date('now','localtime')"]
        monday-expr [:raw "date('now','localtime','-6 days','weekday 1')"]
        where-clause (into [:and user-where]
                           (filter some?
                                   [scope-clause
                                    [:or
                                     [:= :entry_date today-expr]
                                     [:and
                                      [:= :entry_date monday-expr]
                                      [:exists {:select [1]
                                                :from [[:journals :j]]
                                                :where [:and
                                                        [:= :j.id :journal_entries.journal_id]
                                                        [:= :j.schedule_type "weekly"]]}]]]]))
        raw-entries (jdbc/execute! conn
                      (sql/format {:select db/journal-entry-select-columns
                                   :from [:journal_entries]
                                   :where where-clause
                                   :order-by [[:entry_date :desc] [:sort_order :asc]]})
                      db/jdbc-opts)
        entries (->> raw-entries
                     (reduce (fn [{:keys [seen acc]} e]
                               (if (and (:journal_id e) (contains? seen (:journal_id e)))
                                 {:seen seen :acc acc}
                                 {:seen (cond-> seen (:journal_id e) (conj (:journal_id e)))
                                  :acc (conj acc e)}))
                             {:seen #{} :acc []})
                     :acc
                     (sort-by :sort_order)
                     vec)
        entry-ids (mapv :id entries)
        categories-data (when (seq entry-ids)
                          (jdbc/execute! conn
                            (sql/format {:select [:journal_entry_id :category_type :category_id]
                                         :from [:journal_entry_categories]
                                         :where [:in :journal_entry_id entry-ids]})
                            db/jdbc-opts))
        {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
        categories-by-entry (group-by :journal_entry_id categories-data)]
    (-> (associate-categories-with-journal-entries entries categories-by-entry people-by-id places-by-id projects-by-id goals-by-id)
        (relation/associate-relations-with-items "jen" conn))))

(defn journal-entry-owned-by-user? [ds entry-id user-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id]
                        :from [:journal_entries]
                        :where [:and [:= :id entry-id] (db/user-id-where-clause user-id)]})
           db/jdbc-opts)))

(defn get-journal-entry [ds user-id entry-id]
  (let [conn (db/get-conn ds)
        user-where (db/user-id-where-clause user-id)
        entry (jdbc/execute-one! conn
                (sql/format {:select db/journal-entry-select-columns
                             :from [:journal_entries]
                             :where [:and [:= :id entry-id] user-where]})
                db/jdbc-opts)]
    (when entry
      (let [categories-data (jdbc/execute! conn
                              (sql/format {:select [:journal_entry_id :category_type :category_id]
                                           :from [:journal_entry_categories]
                                           :where [:= :journal_entry_id entry-id]})
                              db/jdbc-opts)
            {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
            categories-by-entry (group-by :journal_entry_id categories-data)]
        (first (relation/associate-relations-with-items
                 (associate-categories-with-journal-entries [entry] categories-by-entry people-by-id places-by-id projects-by-id goals-by-id)
                 "jen" conn))))))

(defn update-journal-entry [ds user-id entry-id fields]
  (let [field-names (keys fields)
        set-map (assoc fields :modified_at [:raw "datetime('now')"])
        return-cols (into [:id :created_at :modified_at] field-names)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :journal_entries
                   :set set-map
                   :where [:and [:= :id entry-id] (db/user-id-where-clause user-id)]
                   :returning return-cols})
      db/jdbc-opts)))

(defn delete-journal-entry [ds user-id entry-id]
  (when (journal-entry-owned-by-user? ds entry-id user-id)
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :journal_entry_categories
                       :where [:= :journal_entry_id entry-id]}))
        (relation/delete-relations-for-item tx "jen" entry-id)
        (let [result (jdbc/execute-one! tx
                       (sql/format {:delete-from :journal_entries
                                    :where [:= :id entry-id]}))]
          (tel/log! {:level :info :data {:journal-entry-id entry-id :user-id user-id}} "Journal entry deleted")
          {:success (pos? (:next.jdbc/update-count result))})))))

(defn set-journal-entry-field [ds user-id entry-id field value]
  (let [normalize-fn (get db/field-normalizers field identity)
        valid-value (normalize-fn value)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :journal_entries
                   :set {field valid-value
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id entry-id] (db/user-id-where-clause user-id)]
                   :returning [:id field :modified_at]})
      db/jdbc-opts)))

(defn reorder-journal-entry [ds user-id entry-id new-sort-order]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :journal_entries
                 :set {:sort_order new-sort-order}
                 :where [:and [:= :id entry-id] (db/user-id-where-clause user-id)]}))
  {:success true :sort_order new-sort-order})

(defn categorize-journal-entry [ds user-id entry-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (journal-entry-owned-by-user? ds entry-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:insert-into :journal_entry_categories
                       :values [{:journal_entry_id entry-id
                                 :category_type category-type
                                 :category_id category-id}]
                       :on-conflict []
                       :do-nothing true}))
        (jdbc/execute-one! tx
          (sql/format {:update :journal_entries
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id entry-id]}))))))

(defn uncategorize-journal-entry [ds user-id entry-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (journal-entry-owned-by-user? ds entry-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :journal_entry_categories
                       :where [:and
                               [:= :journal_entry_id entry-id]
                               [:= :category_type category-type]
                               [:= :category_id category-id]]}))
        (jdbc/execute-one! tx
          (sql/format {:update :journal_entries
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id entry-id]}))))))

(defn add-journal-entry [ds user-id title scope]
  (let [conn (db/get-conn ds)
        valid-scope (db/normalize-scope scope)
        min-order (or (:min_order (jdbc/execute-one! conn
                                    (sql/format {:select [[[:min :sort_order] :min_order]]
                                                 :from [:journal_entries]
                                                 :where (db/user-id-where-clause user-id)})
                                    db/jdbc-opts))
                      1.0)
        new-order (- min-order 1.0)
        result (jdbc/execute-one! conn
                 (sql/format {:insert-into :journal_entries
                              :values [{:title title
                                        :sort_order new-order
                                        :user_id user-id
                                        :modified_at [:raw "datetime('now')"]
                                        :scope valid-scope}]
                              :returning db/journal-entry-select-columns})
                 db/jdbc-opts)]
    (tel/log! {:level :info :data {:journal-entry-id (:id result) :user-id user-id}} "Journal entry added")
    (assoc result :people [] :places [] :projects [] :goals [])))
