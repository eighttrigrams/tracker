(ns et.tr.db.meet
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]
            [et.tr.db.relation :as relation]))

(defn add-meet
  ([ds user-id title] (add-meet ds user-id title "both"))
  ([ds user-id title scope]
   (let [conn (db/get-conn ds)
         valid-scope (db/normalize-scope scope)
         min-order (or (:min_order (jdbc/execute-one! conn
                                     (sql/format {:select [[[:min :sort_order] :min_order]]
                                                  :from [:meets]
                                                  :where (db/user-id-where-clause user-id)})
                                     db/jdbc-opts))
                       1.0)
         new-order (- min-order 1.0)
         result (jdbc/execute-one! conn
                  (sql/format {:insert-into :meets
                               :values [{:title title
                                         :sort_order new-order
                                         :user_id user-id
                                         :modified_at [:raw "datetime('now')"]
                                         :start_date [:raw "date('now','localtime')"]
                                         :start_time [:raw "strftime('%H:%M','now','localtime')"]
                                         :scope valid-scope}]
                               :returning (conj db/meet-select-columns :user_id)})
                  db/jdbc-opts)]
     (tel/log! {:level :info :data {:meet-id (:id result) :user-id user-id}} "Meet added")
     (assoc result :people [] :places [] :projects [] :goals []))))

(defn- build-meet-category-clauses [categories]
  (let [people-clause (db/build-category-subquery :meet_categories :meet_id :meets "person" (:people categories))
        places-clause (db/build-category-subquery :meet_categories :meet_id :meets "place" (:places categories))
        projects-clause (db/build-category-subquery :meet_categories :meet_id :meets "project" (:projects categories))
        goals-clause (db/build-category-subquery :meet_categories :meet_id :meets "goal" (:goals categories))]
    (filterv some? [people-clause places-clause projects-clause goals-clause])))

(defn- associate-categories-with-meets [meets categories-by-meet people-by-id places-by-id projects-by-id goals-by-id]
  (mapv (fn [meet]
          (let [meet-categories (get categories-by-meet (:id meet) [])]
            (assoc meet
                   :people (db/extract-category meet-categories "person" people-by-id)
                   :places (db/extract-category meet-categories "place" places-by-id)
                   :projects (db/extract-category meet-categories "project" projects-by-id)
                   :goals (db/extract-category meet-categories "goal" goals-by-id))))
        meets))

(defn list-meets
  ([ds user-id] (list-meets ds user-id {}))
  ([ds user-id opts]
   (let [{:keys [search-term importance context strict categories sort-mode excluded-places excluded-projects series-id]} opts
         conn (db/get-conn ds)
         user-where (db/user-id-where-clause user-id)
         date-clause (case sort-mode
                       :summary nil
                       :past [:or
                              [:< :start_date [:raw "date('now','localtime')"]]
                              [:= :archived 1]]
                       [:and
                        [:>= :start_date [:raw "date('now','localtime')"]]
                        [:= :archived 0]])
         search-clause (db/build-search-clause search-term [:title :tags])
         importance-clause (db/build-importance-clause importance)
         scope-clause (db/build-scope-clause context strict)
         category-clauses (build-meet-category-clauses categories)
         series-clause (when series-id [:= :meeting_series_id series-id])
         exclusion-clauses (filterv some? [(db/build-exclusion-subquery :meet_categories :meet_id :meets "place" excluded-places)
                                           (db/build-exclusion-subquery :meet_categories :meet_id :meets "project" excluded-projects)])
         where-clause (into [:and user-where]
                            (concat (filter some? [date-clause search-clause importance-clause scope-clause series-clause])
                                    category-clauses
                                    exclusion-clauses))
         order-by (case sort-mode
                    :summary [[:start_date :desc] [:start_time :desc]]
                    :past [[:start_date :desc] [:start_time :desc]]
                    [[:start_date :asc] [:start_time :asc]])
         meets (jdbc/execute! conn
                 (sql/format {:select db/meet-select-columns
                              :from [:meets]
                              :where where-clause
                              :order-by order-by})
                 db/jdbc-opts)
         meet-ids (mapv :id meets)
         categories-data (when (seq meet-ids)
                           (jdbc/execute! conn
                             (sql/format {:select [:meet_id :category_type :category_id]
                                          :from [:meet_categories]
                                          :where [:in :meet_id meet-ids]})
                             db/jdbc-opts))
         {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
         categories-by-meet (group-by :meet_id categories-data)
         meets-with-categories (associate-categories-with-meets meets categories-by-meet people-by-id places-by-id projects-by-id goals-by-id)
         series-ids (->> meets (keep :meeting_series_id) distinct vec)
         series-info (when (seq series-ids)
                       (let [today-expr [:raw "date('now','localtime')"]
                             rows (jdbc/execute! conn
                                    (sql/format {:select [:meeting_series.id
                                                          :meeting_series.schedule_days
                                                          :meeting_series.schedule_time
                                                          :meeting_series.schedule_mode
                                                          :meeting_series.biweekly_offset
                                                          [[:exists {:select [1]
                                                                     :from [:meets]
                                                                     :where [:and
                                                                             [:= :meets.meeting_series_id :meeting_series.id]
                                                                             [:> :meets.start_date today-expr]]
                                                                     :limit 1}]
                                                           :has_future_meet]]
                                                  :from [:meeting_series]
                                                  :where [:in :meeting_series.id series-ids]})
                                    db/jdbc-opts)]
                         (into {} (map (fn [r] [(:id r) {:schedule_days (:schedule_days r)
                                                          :schedule_time (:schedule_time r)
                                                          :schedule_mode (:schedule_mode r)
                                                          :biweekly_offset (:biweekly_offset r)
                                                          :series_has_future_meet (= 1 (:has_future_meet r))}]) rows))))
         meets-enriched (if series-info
                          (mapv (fn [m]
                                  (if-let [si (get series-info (:meeting_series_id m))]
                                    (merge m si)
                                    m))
                                meets-with-categories)
                          meets-with-categories)]
     (relation/associate-relations-with-items meets-enriched "met" conn))))

(defn meet-owned-by-user? [ds meet-id user-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id]
                        :from [:meets]
                        :where [:and [:= :id meet-id] (db/user-id-where-clause user-id)]})
           db/jdbc-opts)))

(defn get-meet [ds user-id meet-id]
  (let [conn (db/get-conn ds)
        user-where (db/user-id-where-clause user-id)
        meet (jdbc/execute-one! conn
               (sql/format {:select db/meet-select-columns
                            :from [:meets]
                            :where [:and [:= :id meet-id] user-where]})
               db/jdbc-opts)]
    (when meet
      (let [categories-data (jdbc/execute! conn
                              (sql/format {:select [:meet_id :category_type :category_id]
                                           :from [:meet_categories]
                                           :where [:= :meet_id meet-id]})
                              db/jdbc-opts)
            {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
            categories-by-meet (group-by :meet_id categories-data)]
        (first (associate-categories-with-meets [meet] categories-by-meet people-by-id places-by-id projects-by-id goals-by-id))))))

(defn update-meet [ds user-id meet-id fields]
  (let [set-map (assoc fields :modified_at [:raw "datetime('now')"])
        return-cols (into [:id :created_at :modified_at] (keys fields))]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :meets
                   :set set-map
                   :where [:and [:= :id meet-id] (db/user-id-where-clause user-id)]
                   :returning return-cols})
      db/jdbc-opts)))

(defn delete-meet [ds user-id meet-id]
  (when (meet-owned-by-user? ds meet-id user-id)
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :meet_categories
                       :where [:= :meet_id meet-id]}))
        (jdbc/execute-one! tx
          (sql/format {:delete-from :relations
                       :where [:or
                               [:and [:= :source_type "met"] [:= :source_id meet-id]]
                               [:and [:= :target_type "met"] [:= :target_id meet-id]]]}))
        (let [result (jdbc/execute-one! tx
                       (sql/format {:delete-from :meets
                                    :where [:= :id meet-id]}))]
          (tel/log! {:level :info :data {:meet-id meet-id :user-id user-id}} "Meet deleted")
          {:success (pos? (:next.jdbc/update-count result))})))))

(defn set-meet-field [ds user-id meet-id field value]
  (let [normalize-fn (get db/field-normalizers field identity)
        valid-value (normalize-fn value)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :meets
                   :set {field valid-value
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id meet-id] (db/user-id-where-clause user-id)]
                   :returning [:id field :modified_at]})
      db/jdbc-opts)))

(defn set-meet-start-date [ds user-id meet-id start-date]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :meets
                 :set {:start_date start-date
                       :modified_at [:raw "datetime('now')"]}
                 :where [:and [:= :id meet-id] (db/user-id-where-clause user-id)]
                 :returning [:id :start_date :start_time :modified_at]})
    db/jdbc-opts))

(defn set-meet-start-time [ds user-id meet-id start-time]
  (let [normalized-time (if (empty? start-time) nil start-time)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :meets
                   :set {:start_time normalized-time
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id meet-id] (db/user-id-where-clause user-id)]
                   :returning [:id :start_date :start_time :modified_at]})
      db/jdbc-opts)))

(defn archive-meet [ds user-id meet-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :meets
                 :set {:archived 1
                       :modified_at [:raw "datetime('now')"]}
                 :where [:and [:= :id meet-id] (db/user-id-where-clause user-id)]
                 :returning [:id :archived :modified_at]})
    db/jdbc-opts))

(defn categorize-meet [ds user-id meet-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (meet-owned-by-user? ds meet-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:insert-into :meet_categories
                       :values [{:meet_id meet-id
                                 :category_type category-type
                                 :category_id category-id}]
                       :on-conflict []
                       :do-nothing true}))
        (jdbc/execute-one! tx
          (sql/format {:update :meets
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id meet-id]}))))))

(defn uncategorize-meet [ds user-id meet-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (meet-owned-by-user? ds meet-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :meet_categories
                       :where [:and
                               [:= :meet_id meet-id]
                               [:= :category_type category-type]
                               [:= :category_id category-id]]}))
        (jdbc/execute-one! tx
          (sql/format {:update :meets
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id meet-id]}))))))
