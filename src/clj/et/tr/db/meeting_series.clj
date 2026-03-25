(ns et.tr.db.meeting-series
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [clojure.string :as str]
            [et.tr.db :as db])
  (:import [java.time LocalDate DayOfWeek]))

(defn add-meeting-series
  ([ds user-id title] (add-meeting-series ds user-id title "both"))
  ([ds user-id title scope]
   (let [conn (db/get-conn ds)
         valid-scope (db/normalize-scope scope)
         min-order (or (:min_order (jdbc/execute-one! conn
                                     (sql/format {:select [[[:min :sort_order] :min_order]]
                                                  :from [:meeting_series]
                                                  :where (db/user-id-where-clause user-id)})
                                     db/jdbc-opts))
                       1.0)
         new-order (- min-order 1.0)
         result (jdbc/execute-one! conn
                  (sql/format {:insert-into :meeting_series
                               :values [{:title title
                                         :sort_order new-order
                                         :user_id user-id
                                         :modified_at [:raw "datetime('now')"]
                                         :scope valid-scope}]
                               :returning db/meeting-series-select-columns})
                  db/jdbc-opts)]
     (tel/log! {:level :info :data {:meeting-series-id (:id result) :user-id user-id}} "Meeting series added")
     (assoc result :people [] :places [] :projects [] :goals []))))

(defn- build-meeting-series-category-clauses [categories]
  (let [people-clause (db/build-category-subquery :meeting_series_categories :meeting_series_id :meeting_series "person" (:people categories))
        places-clause (db/build-category-subquery :meeting_series_categories :meeting_series_id :meeting_series "place" (:places categories))
        projects-clause (db/build-category-subquery :meeting_series_categories :meeting_series_id :meeting_series "project" (:projects categories))
        goals-clause (db/build-category-subquery :meeting_series_categories :meeting_series_id :meeting_series "goal" (:goals categories))]
    (filterv some? [people-clause places-clause projects-clause goals-clause])))

(defn- associate-categories-with-meeting-series [series categories-by-series people-by-id places-by-id projects-by-id goals-by-id]
  (mapv (fn [s]
          (let [series-categories (get categories-by-series (:id s) [])]
            (assoc s
                   :people (db/extract-category series-categories "person" people-by-id)
                   :places (db/extract-category series-categories "place" places-by-id)
                   :projects (db/extract-category series-categories "project" projects-by-id)
                   :goals (db/extract-category series-categories "goal" goals-by-id))))
        series))

(defn list-meeting-series
  ([ds user-id] (list-meeting-series ds user-id {}))
  ([ds user-id opts]
   (let [{:keys [search-term context strict categories]} opts
         conn (db/get-conn ds)
         user-where (db/user-id-where-clause user-id)
         search-clause (db/build-search-clause search-term [:title :tags])
         scope-clause (db/build-scope-clause context strict)
         category-clauses (build-meeting-series-category-clauses categories)
         where-clause (into [:and user-where]
                            (concat (filter some? [search-clause scope-clause])
                                    category-clauses))
         today-expr [:raw "date('now','localtime')"]
         has-today-meet {:select [1]
                         :from [:meets]
                         :where [:and
                                 [:= :meets.meeting_series_id :meeting_series.id]
                                 [:= :meets.start_date today-expr]]
                         :limit 1}
         has-future-meet {:select [1]
                          :from [:meets]
                          :where [:and
                                  [:= :meets.meeting_series_id :meeting_series.id]
                                  [:> :meets.start_date today-expr]]
                          :limit 1}
         series (jdbc/execute! conn
                  (sql/format {:select (into db/meeting-series-select-columns
                                             [[[:exists has-today-meet] :has_today_meet]
                                              [[:exists has-future-meet] :has_future_meet]])
                               :from [:meeting_series]
                               :where where-clause
                               :order-by [[:sort_order :asc]]})
                  db/jdbc-opts)
         series (mapv (fn [s]
                        (-> s
                            (update :has_today_meet #(= 1 %))
                            (update :has_future_meet #(= 1 %))))
                      series)
         series-ids (mapv :id series)
         categories-data (when (seq series-ids)
                           (jdbc/execute! conn
                             (sql/format {:select [:meeting_series_id :category_type :category_id]
                                          :from [:meeting_series_categories]
                                          :where [:in :meeting_series_id series-ids]})
                             db/jdbc-opts))
         {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
         categories-by-series (group-by :meeting_series_id categories-data)]
     (associate-categories-with-meeting-series series categories-by-series people-by-id places-by-id projects-by-id goals-by-id))))

(defn meeting-series-owned-by-user? [ds series-id user-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id]
                        :from [:meeting_series]
                        :where [:and [:= :id series-id] (db/user-id-where-clause user-id)]})
           db/jdbc-opts)))

(defn get-meeting-series [ds user-id series-id]
  (let [conn (db/get-conn ds)
        user-where (db/user-id-where-clause user-id)
        series (jdbc/execute-one! conn
                 (sql/format {:select db/meeting-series-select-columns
                              :from [:meeting_series]
                              :where [:and [:= :id series-id] user-where]})
                 db/jdbc-opts)]
    (when series
      (let [categories-data (jdbc/execute! conn
                              (sql/format {:select [:meeting_series_id :category_type :category_id]
                                           :from [:meeting_series_categories]
                                           :where [:= :meeting_series_id series-id]})
                              db/jdbc-opts)
            {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
            categories-by-series (group-by :meeting_series_id categories-data)]
        (first (associate-categories-with-meeting-series [series] categories-by-series people-by-id places-by-id projects-by-id goals-by-id))))))

(defn update-meeting-series [ds user-id series-id fields]
  (let [set-map (assoc fields :modified_at [:raw "datetime('now')"])
        return-cols (into [:id :created_at :modified_at] (keys fields))]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :meeting_series
                   :set set-map
                   :where [:and [:= :id series-id] (db/user-id-where-clause user-id)]
                   :returning return-cols})
      db/jdbc-opts)))

(defn delete-meeting-series [ds user-id series-id]
  (when (meeting-series-owned-by-user? ds series-id user-id)
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:update :meets
                       :set {:meeting_series_id nil}
                       :where [:= :meeting_series_id series-id]}))
        (jdbc/execute-one! tx
          (sql/format {:delete-from :meeting_series_categories
                       :where [:= :meeting_series_id series-id]}))
        (let [result (jdbc/execute-one! tx
                       (sql/format {:delete-from :meeting_series
                                    :where [:= :id series-id]}))]
          (tel/log! {:level :info :data {:meeting-series-id series-id :user-id user-id}} "Meeting series deleted")
          {:success (pos? (:next.jdbc/update-count result))})))))

(defn set-meeting-series-field [ds user-id series-id field value]
  (let [normalize-fn (get db/field-normalizers field identity)
        valid-value (normalize-fn value)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :meeting_series
                   :set {field valid-value
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id series-id] (db/user-id-where-clause user-id)]
                   :returning [:id field :modified_at]})
      db/jdbc-opts)))

(defn set-meeting-series-schedule [ds user-id series-id schedule-days schedule-time schedule-mode biweekly-offset]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :meeting_series
                 :set {:schedule_days (or schedule-days "")
                       :schedule_time schedule-time
                       :schedule_mode (or schedule-mode "weekly")
                       :biweekly_offset (if biweekly-offset 1 0)
                       :modified_at [:raw "datetime('now')"]}
                 :where [:and [:= :id series-id] (db/user-id-where-clause user-id)]
                 :returning [:id :schedule_days :schedule_time :schedule_mode :biweekly_offset :modified_at]})
    db/jdbc-opts))

(defn create-meeting-for-series [ds user-id series-id date time]
  (when (meeting-series-owned-by-user? ds series-id user-id)
    (let [conn (db/get-conn ds)
          series (jdbc/execute-one! conn
                   (sql/format {:select [:title :scope]
                                :from [:meeting_series]
                                :where [:= :id series-id]})
                   db/jdbc-opts)]
      (when series
        (jdbc/with-transaction [tx conn]
          (let [min-order (or (:min_order (jdbc/execute-one! tx
                                            (sql/format {:select [[[:min :sort_order] :min_order]]
                                                         :from [:meets]
                                                         :where (db/user-id-where-clause user-id)})
                                            db/jdbc-opts))
                              1.0)
                new-order (- min-order 1.0)
                meet (jdbc/execute-one! tx
                       (sql/format {:insert-into :meets
                                    :values [{:title (:title series)
                                              :sort_order new-order
                                              :user_id user-id
                                              :modified_at [:raw "datetime('now')"]
                                              :start_date date
                                              :start_time time
                                              :scope (:scope series)
                                              :meeting_series_id series-id}]
                                    :returning db/meet-select-columns})
                       db/jdbc-opts)
                series-cats (jdbc/execute! tx
                              (sql/format {:select [:category_type :category_id]
                                           :from [:meeting_series_categories]
                                           :where [:= :meeting_series_id series-id]})
                              db/jdbc-opts)]
            (doseq [{:keys [category_type category_id]} series-cats]
              (jdbc/execute-one! tx
                (sql/format {:insert-into :meet_categories
                             :values [{:meet_id (:id meet)
                                       :category_type category_type
                                       :category_id category_id}]
                             :on-conflict []
                             :do-nothing true})))
            (tel/log! {:level :info :data {:meet-id (:id meet) :series-id series-id :user-id user-id}} "Meet created from series")
            (assoc meet :people [] :places [] :projects [] :goals [])))))))

(defn categorize-meeting-series [ds user-id series-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (meeting-series-owned-by-user? ds series-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:insert-into :meeting_series_categories
                       :values [{:meeting_series_id series-id
                                 :category_type category-type
                                 :category_id category-id}]
                       :on-conflict []
                       :do-nothing true}))
        (jdbc/execute-one! tx
          (sql/format {:update :meeting_series
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id series-id]}))))))

(defn uncategorize-meeting-series [ds user-id series-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (meeting-series-owned-by-user? ds series-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :meeting_series_categories
                       :where [:and
                               [:= :meeting_series_id series-id]
                               [:= :category_type category-type]
                               [:= :category_id category-id]]}))
        (jdbc/execute-one! tx
          (sql/format {:update :meeting_series
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id series-id]}))))))

(defn- get-schedule-time-for-day [schedule-time day-num]
  (if (and (some? schedule-time) (str/includes? schedule-time "="))
    (some (fn [pair]
            (let [[d t] (str/split pair #"=" 2)]
              (when (= d (str day-num)) t)))
          (str/split schedule-time #","))
    schedule-time))

(defn- next-weekly-date [schedule-days-set ^LocalDate start-date]
  (loop [d start-date i 0]
    (when (< i 8)
      (let [day-num (.getValue (.getDayOfWeek d))]
        (if (contains? schedule-days-set (str day-num))
          {:date (str d) :day-num day-num}
          (recur (.plusDays d 1) (inc i)))))))

(defn- next-monthly-date [day-of-month ^LocalDate start-date]
  (let [dom (Integer/parseInt day-of-month)
        candidate (if (>= dom (.getDayOfMonth start-date))
                    (.withDayOfMonth start-date dom)
                    (.withDayOfMonth (.plusMonths start-date 1) dom))]
    {:date (str candidate) :day-num nil}))

(defn- first-monday-of-year []
  (let [year (.getYear (LocalDate/now))
        jan1 (LocalDate/of year 1 1)
        dow (.getValue (.getDayOfWeek jan1))
        offset (if (<= dow 1) (- 1 dow) (- 8 dow))]
    (.plusDays jan1 offset)))

(defn- biweekly-anchor [offset-flag]
  (let [mon1 (first-monday-of-year)]
    (if (= 1 offset-flag) (.plusDays mon1 7) mon1)))

(defn- next-biweekly-date [day-of-week anchor-str ^LocalDate start-date]
  (let [dow (Integer/parseInt day-of-week)
        anchor (biweekly-anchor anchor-str)]
    (loop [d start-date i 0]
      (when (< i 15)
        (let [d-dow (.getValue (.getDayOfWeek d))
              days-between (.until anchor d java.time.temporal.ChronoUnit/DAYS)
              weeks-since (quot days-between 7)]
          (if (and (= d-dow dow) (even? weeks-since))
            {:date (str d) :day-num dow}
            (recur (.plusDays d 1) (inc i))))))))

(defn- is-today-scheduled? [mode schedule-days-set biweekly-offset ^LocalDate today]
  (case mode
    "monthly"
    (let [dom (first schedule-days-set)]
      (= (str (.getDayOfMonth today)) dom))

    "biweekly"
    (let [dow (first schedule-days-set)
          anchor (biweekly-anchor biweekly-offset)
          today-dow (.getValue (.getDayOfWeek today))
          days-between (.until anchor today java.time.temporal.ChronoUnit/DAYS)
          weeks-since (quot days-between 7)]
      (and (= today-dow (Integer/parseInt dow)) (even? weeks-since)))

    (contains? schedule-days-set (str (.getValue (.getDayOfWeek today))))))

(defn- next-scheduled-date [mode schedule-days-set schedule-time biweekly-offset ^LocalDate start-date]
  (case mode
    "monthly"
    (let [dom (first schedule-days-set)]
      (next-monthly-date dom start-date))

    "biweekly"
    (let [dow (first schedule-days-set)]
      (next-biweekly-date dow biweekly-offset start-date))

    (next-weekly-date schedule-days-set start-date)))

(defn auto-create-meetings [ds user-id]
  (let [conn (db/get-conn ds)
        today-expr [:raw "date('now','localtime')"]
        has-today-meet {:select [1]
                        :from [:meets]
                        :where [:and
                                [:= :meets.meeting_series_id :meeting_series.id]
                                [:= :meets.start_date today-expr]]
                        :limit 1}
        has-future-meet {:select [1]
                         :from [:meets]
                         :where [:and
                                 [:= :meets.meeting_series_id :meeting_series.id]
                                 [:> :meets.start_date today-expr]]
                         :limit 1}
        all-series (jdbc/execute! conn
                     (sql/format {:select [:id :schedule_days :schedule_time :schedule_mode :biweekly_offset
                                           [[:exists has-today-meet] :has_today_meet]
                                           [[:exists has-future-meet] :has_future_meet]]
                                  :from [:meeting_series]
                                  :where [:and
                                          (db/user-id-where-clause user-id)
                                          [:!= :schedule_days ""]]})
                     db/jdbc-opts)
        all-series (mapv (fn [s]
                           (-> s
                               (update :has_today_meet #(= 1 %))
                               (update :has_future_meet #(= 1 %))))
                         all-series)
        today (LocalDate/now)
        created (atom [])]
    (doseq [{:keys [id schedule_days schedule_time schedule_mode biweekly_offset has_today_meet has_future_meet]} all-series]
      (let [mode (or schedule_mode "weekly")
            schedule-days-set (set (str/split schedule_days #","))]
        (when (and (not has_today_meet)
                   (is-today-scheduled? mode schedule-days-set biweekly_offset today))
          (let [today-day (.getValue (.getDayOfWeek today))
                time (get-schedule-time-for-day schedule_time today-day)]
            (when-let [meet (create-meeting-for-series ds user-id id (str today) time)]
              (swap! created conj meet))))
        (when (not has_future_meet)
          (when-let [{:keys [date day-num]} (next-scheduled-date mode schedule-days-set schedule_time biweekly_offset (.plusDays today 1))]
            (let [time (if day-num
                         (get-schedule-time-for-day schedule_time day-num)
                         schedule_time)]
              (when-let [meet (create-meeting-for-series ds user-id id date time)]
                (swap! created conj meet)))))))
    @created))
