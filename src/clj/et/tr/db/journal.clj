(ns et.tr.db.journal
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.db :as db])
  (:import [java.time LocalDate DayOfWeek]
           [java.time.temporal TemporalAdjusters]))

(defn add-journal
  ([ds user-id title] (add-journal ds user-id title "both" "daily"))
  ([ds user-id title scope schedule-type]
   (let [conn (db/get-conn ds)
         valid-scope (db/normalize-scope scope)
         valid-type (if (#{"daily" "weekly"} schedule-type) schedule-type "daily")
         min-order (or (:min_order (jdbc/execute-one! conn
                                     (sql/format {:select [[[:min :sort_order] :min_order]]
                                                  :from [:journals]
                                                  :where (db/user-id-where-clause user-id)})
                                     db/jdbc-opts))
                       1.0)
         new-order (- min-order 1.0)
         result (jdbc/execute-one! conn
                  (sql/format {:insert-into :journals
                               :values [{:title title
                                         :sort_order new-order
                                         :user_id user-id
                                         :modified_at [:raw "datetime('now')"]
                                         :scope valid-scope
                                         :schedule_type valid-type}]
                               :returning db/journal-select-columns})
                  db/jdbc-opts)]
     (tel/log! {:level :info :data {:journal-id (:id result) :user-id user-id}} "Journal added")
     (assoc result :people [] :places [] :projects [] :goals []))))

(defn- build-journal-category-clauses [categories]
  (let [people-clause (db/build-category-subquery :journal_categories :journal_id :journals "person" (:people categories))
        places-clause (db/build-category-subquery :journal_categories :journal_id :journals "place" (:places categories))
        projects-clause (db/build-category-subquery :journal_categories :journal_id :journals "project" (:projects categories))
        goals-clause (db/build-category-subquery :journal_categories :journal_id :journals "goal" (:goals categories))]
    (filterv some? [people-clause places-clause projects-clause goals-clause])))

(defn- associate-categories-with-journals [journals categories-by-journal people-by-id places-by-id projects-by-id goals-by-id]
  (mapv (fn [j]
          (let [journal-categories (get categories-by-journal (:id j) [])]
            (assoc j
                   :people (db/extract-category journal-categories "person" people-by-id)
                   :places (db/extract-category journal-categories "place" places-by-id)
                   :projects (db/extract-category journal-categories "project" projects-by-id)
                   :goals (db/extract-category journal-categories "goal" goals-by-id))))
        journals))

(defn list-journals
  ([ds user-id] (list-journals ds user-id {}))
  ([ds user-id opts]
   (let [{:keys [search-term context strict categories]} opts
         conn (db/get-conn ds)
         user-where (db/user-id-where-clause user-id)
         search-clause (db/build-search-clause search-term [:title :tags])
         scope-clause (db/build-scope-clause context strict)
         category-clauses (build-journal-category-clauses categories)
         where-clause (into [:and user-where]
                            (concat (filter some? [search-clause scope-clause])
                                    category-clauses))
         journals (jdbc/execute! conn
                    (sql/format {:select db/journal-select-columns
                                 :from [:journals]
                                 :where where-clause
                                 :order-by [[:sort_order :asc]]})
                    db/jdbc-opts)
         journal-ids (mapv :id journals)
         categories-data (when (seq journal-ids)
                           (jdbc/execute! conn
                             (sql/format {:select [:journal_id :category_type :category_id]
                                          :from [:journal_categories]
                                          :where [:in :journal_id journal-ids]})
                             db/jdbc-opts))
         {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
         categories-by-journal (group-by :journal_id categories-data)]
     (associate-categories-with-journals journals categories-by-journal people-by-id places-by-id projects-by-id goals-by-id))))

(defn journal-owned-by-user? [ds journal-id user-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id]
                        :from [:journals]
                        :where [:and [:= :id journal-id] (db/user-id-where-clause user-id)]})
           db/jdbc-opts)))

(defn get-journal [ds user-id journal-id]
  (let [conn (db/get-conn ds)
        user-where (db/user-id-where-clause user-id)
        journal (jdbc/execute-one! conn
                  (sql/format {:select db/journal-select-columns
                               :from [:journals]
                               :where [:and [:= :id journal-id] user-where]})
                  db/jdbc-opts)]
    (when journal
      (let [categories-data (jdbc/execute! conn
                              (sql/format {:select [:journal_id :category_type :category_id]
                                           :from [:journal_categories]
                                           :where [:= :journal_id journal-id]})
                              db/jdbc-opts)
            {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
            categories-by-journal (group-by :journal_id categories-data)]
        (first (associate-categories-with-journals [journal] categories-by-journal people-by-id places-by-id projects-by-id goals-by-id))))))

(defn update-journal [ds user-id journal-id fields]
  (let [set-map (assoc fields :modified_at [:raw "datetime('now')"])
        return-cols (into [:id :created_at :modified_at] (keys fields))]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :journals
                   :set set-map
                   :where [:and [:= :id journal-id] (db/user-id-where-clause user-id)]
                   :returning return-cols})
      db/jdbc-opts)))

(defn delete-journal [ds user-id journal-id]
  (when (journal-owned-by-user? ds journal-id user-id)
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:update :journal_entries
                       :set {:journal_id nil}
                       :where [:= :journal_id journal-id]}))
        (jdbc/execute-one! tx
          (sql/format {:delete-from :journal_categories
                       :where [:= :journal_id journal-id]}))
        (let [result (jdbc/execute-one! tx
                       (sql/format {:delete-from :journals
                                    :where [:= :id journal-id]}))]
          (tel/log! {:level :info :data {:journal-id journal-id :user-id user-id}} "Journal deleted")
          {:success (pos? (:next.jdbc/update-count result))})))))

(defn set-journal-field [ds user-id journal-id field value]
  (let [normalize-fn (get db/field-normalizers field identity)
        valid-value (normalize-fn value)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :journals
                   :set {field valid-value
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id journal-id] (db/user-id-where-clause user-id)]
                   :returning [:id field :modified_at]})
      db/jdbc-opts)))

(defn categorize-journal [ds user-id journal-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (journal-owned-by-user? ds journal-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:insert-into :journal_categories
                       :values [{:journal_id journal-id
                                 :category_type category-type
                                 :category_id category-id}]
                       :on-conflict []
                       :do-nothing true}))
        (jdbc/execute-one! tx
          (sql/format {:update :journals
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id journal-id]}))))))

(defn uncategorize-journal [ds user-id journal-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (journal-owned-by-user? ds journal-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :journal_categories
                       :where [:and
                               [:= :journal_id journal-id]
                               [:= :category_type category-type]
                               [:= :category_id category-id]]}))
        (jdbc/execute-one! tx
          (sql/format {:update :journals
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id journal-id]}))))))

(defn create-entry-for-journal [ds user-id journal-id date]
  (when (journal-owned-by-user? ds journal-id user-id)
    (let [conn (db/get-conn ds)
          journal (jdbc/execute-one! conn
                    (sql/format {:select [:title :scope]
                                 :from [:journals]
                                 :where [:= :id journal-id]})
                    db/jdbc-opts)]
      (when journal
        (jdbc/with-transaction [tx conn]
          (let [min-order (or (:min_order (jdbc/execute-one! tx
                                            (sql/format {:select [[[:min :sort_order] :min_order]]
                                                         :from [:journal_entries]
                                                         :where (db/user-id-where-clause user-id)})
                                            db/jdbc-opts))
                              1.0)
                new-order (- min-order 1.0)
                entry (jdbc/execute-one! tx
                        (sql/format {:insert-into :journal_entries
                                     :values [{:title (:title journal)
                                               :sort_order new-order
                                               :user_id user-id
                                               :modified_at [:raw "datetime('now')"]
                                               :entry_date date
                                               :scope (:scope journal)
                                               :journal_id journal-id}]
                                     :returning db/journal-entry-select-columns})
                        db/jdbc-opts)
                journal-cats (jdbc/execute! tx
                               (sql/format {:select [:category_type :category_id]
                                            :from [:journal_categories]
                                            :where [:= :journal_id journal-id]})
                               db/jdbc-opts)]
            (doseq [{:keys [category_type category_id]} journal-cats]
              (jdbc/execute-one! tx
                (sql/format {:insert-into :journal_entry_categories
                             :values [{:journal_entry_id (:id entry)
                                       :category_type category_type
                                       :category_id category_id}]
                             :on-conflict []
                             :do-nothing true})))
            (tel/log! {:level :info :data {:entry-id (:id entry) :journal-id journal-id :user-id user-id}} "Journal entry created from journal")
            (assoc entry :people [] :places [] :projects [] :goals [])))))))

(defn- monday-of-week [^LocalDate date]
  (.with date (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY)))

(defn auto-create-journal-entries
  ([ds user-id] (auto-create-journal-entries ds user-id {}))
  ([ds user-id _opts]
   (let [conn (db/get-conn ds)
         all-journals (jdbc/execute! conn
                        (sql/format {:select [:id :schedule_type]
                                     :from [:journals]
                                     :where (db/user-id-where-clause user-id)})
                        db/jdbc-opts)
         today (LocalDate/now)
         today-str (str today)
         monday-str (str (monday-of-week today))
         created (atom [])]
     (doseq [{:keys [id schedule_type]} all-journals]
       (let [target-date (if (= schedule_type "weekly") monday-str today-str)
             existing (jdbc/execute-one! conn
                        (sql/format {:select [1]
                                     :from [:journal_entries]
                                     :where [:and
                                             [:= :journal_id id]
                                             [:= :entry_date target-date]]
                                     :limit 1})
                        db/jdbc-opts)]
         (when-not existing
           (when-let [entry (create-entry-for-journal ds user-id id target-date)]
             (swap! created conj entry)))))
     @created)))
