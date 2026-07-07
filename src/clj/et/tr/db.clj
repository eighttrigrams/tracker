(ns et.tr.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [et.tr.migrations :as migrations]
            [clojure.string :as str]
            [honey.sql :as sql]
            [buddy.hashers :as hashers]
            [taoensso.telemere :as tel]))

(def jdbc-opts {:builder-fn rs/as-unqualified-maps})

(defn- sync-mail-user-password! [conn]
  (when-let [admin-pw (System/getenv "ADMIN_PASSWORD")]
    (when-let [mail-user (jdbc/execute-one! conn
                           (sql/format {:select [:id :password_hash]
                                        :from [:users]
                                        :where [:= :has_mail 1]})
                           jdbc-opts)]
      (when-not (hashers/check admin-pw (:password_hash mail-user))
        (jdbc/execute-one! conn
          (sql/format {:update :users
                       :set {:password_hash (hashers/derive admin-pw)}
                       :where [:= :id (:id mail-user)]}))
        (tel/log! :info "Synced mail user password with ADMIN_PASSWORD")))))

(defn init-conn [{:keys [type path]}]
  (let [db-spec (case type
                  ;; Per-op connections (see below) share the in-memory DB via
                  ;; cache=shared. Two pragmas keep that safe under the SPA's
                  ;; concurrent requests:
                  ;;  - busy_timeout: a writer waits for a peer's write lock
                  ;;    instead of failing immediately with SQLITE_BUSY.
                  ;;  - read_uncommitted: readers don't take a read lock, so a
                  ;;    read that touches a table another connection is writing
                  ;;    no longer dies with SQLITE_LOCKED_SHAREDCACHE (which
                  ;;    busy_timeout does NOT retry). Without it, e.g. loading
                  ;;    the filtered issues list while a category write is in
                  ;;    flight 500s intermittently. Dirty reads are harmless
                  ;;    here: the DB is single-user and e2e asserts settled state.
                  :sqlite-memory {:dbtype "sqlite" :dbname "file::memory:?cache=shared&busy_timeout=5000&read_uncommitted=true"}
                  :sqlite-file {:dbtype "sqlite" :dbname path})
        ds (jdbc/get-datasource db-spec)
        ;; A shared-cache in-memory DB is dropped the instant its last
        ;; connection closes, so we hold one connection open for the whole
        ;; process purely to keep the database alive. We must NOT route request
        ;; traffic through it: a java.sql.Connection is not thread-safe, and the
        ;; SPA fans out concurrent requests — sharing one connection interleaves
        ;; their transactions and silently loses writes (see the categorize /
        ;; reminder e2e flakes). Instead every operation gets a fresh connection
        ;; from the datasource (exactly like file mode); all connections see the
        ;; same data via cache=shared. File mode needs no keep-alive connection.
        persistent-conn (when (= type :sqlite-memory) (jdbc/get-connection ds))]
    (migrations/migrate! ds)
    (sync-mail-user-password! ds)
    {:conn ds
     :persistent-conn persistent-conn
     :type type}))

(defn get-conn [ds]
  (if (map? ds) (:conn ds) ds))

(def valid-scopes #{"private" "both" "work"})

(defn normalize-scope [scope]
  (if (contains? valid-scopes scope) scope "both"))

(def valid-importances #{"normal" "important" "critical"})

(defn- normalize-importance [importance]
  (if (contains? valid-importances importance) importance "normal"))

(def valid-urgencies #{"default" "urgent" "superurgent"})

(defn- normalize-urgency [urgency]
  (if (contains? valid-urgencies urgency) urgency "default"))

(def valid-time-windows #{"both" "daytime" "nighttime"})

(defn- normalize-time-window [v]
  (if (contains? valid-time-windows v) v "both"))

(def field-normalizers
  {:scope normalize-scope
   :importance normalize-importance
   :urgency normalize-urgency
   :time_window normalize-time-window})

(def task-select-columns [:id :title :description :tags :created_at :modified_at :due_date :due_time :sort_order :done :done_at :scope :importance :urgency :today :lined_up_for :maybe :recurring_task_id :issue_id :reminder :reminder_date :relation_badge_title])

(def resource-select-columns [:id :title :link :description :tags :created_at :modified_at :sort_order :scope :importance :relation_badge_title])

(def issue-select-columns [:id :title :description :tags :created_at :modified_at :sort_order :scope :importance :urgency :relation_badge_title])

(def meet-select-columns [:id :title :description :tags :created_at :modified_at :sort_order :scope :importance :start_date :start_time :meeting_series_id :archived :maybe :over :relation_badge_title])

(def meeting-series-select-columns [:id :title :description :tags :created_at :modified_at :sort_order :scope :schedule_days :schedule_time :schedule_mode :biweekly_offset :maybe])

(def recurring-task-select-columns [:id :title :description :tags :created_at :modified_at :sort_order :scope :schedule_days :schedule_time :schedule_mode :biweekly_offset :task_type])

(def journal-select-columns [:id :title :description :tags :created_at :modified_at :sort_order :scope :schedule_type])

(def journal-entry-select-columns [:id :title :description :tags :created_at :modified_at :sort_order :scope :importance :entry_date :journal_id :relation_badge_title])

(def motto-select-columns [:id :title :description :scope :time_window :created_at :modified_at])

(defn user-id-where-clause [user-id]
  (if user-id
    [:= :user_id user-id]
    [:is :user_id nil]))

(defn update-where
  "WHERE clause for an owned-by-user update, with an optional optimistic-
  concurrency guard on modified_at. When expected-modified-at is non-nil the
  update only matches while the stored timestamp is unchanged; otherwise it
  behaves as a plain id + user match (last-write-wins)."
  [id user-id expected-modified-at]
  (cond-> [:and [:= :id id] (user-id-where-clause user-id)]
    expected-modified-at (conj [:= :modified_at expected-modified-at])))

(defn extract-category [task-categories category-type lookup-map]
  (->> task-categories
       (filter #(= (:category_type %) category-type))
       (keep #(when-let [entry (lookup-map (:category_id %))]
                {:id (:category_id %) :name (:name entry) :badge_title (:badge_title entry)}))
       vec))

(defn associate-categories-with-tasks [tasks categories-by-task people-by-id places-by-id projects-by-id goals-by-id]
  (mapv (fn [task]
          (let [task-categories (get categories-by-task (:id task) [])]
            (assoc task
                   :people (extract-category task-categories "person" people-by-id)
                   :places (extract-category task-categories "place" places-by-id)
                   :projects (extract-category task-categories "project" projects-by-id)
                   :goals (extract-category task-categories "goal" goals-by-id))))
        tasks))

(defn build-search-clause
  ([search-term] (build-search-clause search-term [:title :tags]))
  ([search-term columns]
   (when (and search-term (not (str/blank? search-term)))
     (let [terms (->> (str/split (str/trim search-term) #"\s+")
                      (map str/lower-case)
                      (filter (complement str/blank?)))]
       (when (seq terms)
         (into [:and]
               (map (fn [term]
                      (into [:or]
                            (mapcat (fn [col]
                                      (into [[:like [:lower col] (str term "%")]
                                             [:like [:lower col] (str "% " term "%")]]
                                            (map (fn [ch] [:like [:lower col] (str "%" ch term "%")])
                                                 ["\"" "'" "(" "{" "[" "<"])))
                                    columns)))
                    terms)))))))

(defn build-category-subquery
  ([category-type category-names]
   (build-category-subquery :task_categories :task_id :tasks category-type category-names))
  ([join-table entity-id-col entity-ref category-type category-names]
   (when (seq category-names)
     (let [table-name (case category-type
                        "person" :people
                        "place" :places
                        "project" :projects
                        "goal" :goals)
           entity-ref-id (keyword (str (name entity-ref) ".id"))]
       [:exists {:select [1]
                 :from [join-table]
                 :join [[table-name] [:= (keyword (str (name table-name) ".id")) (keyword (str (name join-table) ".category_id"))]]
                 :where [:and
                         [:= (keyword (str (name join-table) "." (name entity-id-col))) entity-ref-id]
                         [:= (keyword (str (name join-table) ".category_type")) category-type]
                         [:in (keyword (str (name table-name) ".name")) category-names]]}]))))

(defn build-exclusion-subquery
  ([category-type category-names]
   (build-exclusion-subquery :task_categories :task_id :tasks category-type category-names))
  ([join-table entity-id-col entity-ref category-type category-names]
   (when (seq category-names)
     (let [table-name (case category-type
                        "place" :places
                        "project" :projects)]
       [:not [:exists {:select [1]
                       :from [join-table]
                       :join [[table-name] [:= (keyword (str (name table-name) ".id")) (keyword (str (name join-table) ".category_id"))]]
                       :where [:and
                               [:= (keyword (str (name join-table) "." (name entity-id-col))) (keyword (str (name entity-ref) ".id"))]
                               [:= (keyword (str (name join-table) ".category_type")) category-type]
                               [:in (keyword (str (name table-name) ".name")) category-names]]}]]))))

(defn fetch-category-lookups [conn user-id-where-clause]
  (let [cols [:id :name :badge_title]
        people (jdbc/execute! conn
                 (sql/format {:select cols
                              :from [:people]
                              :where user-id-where-clause})
                 jdbc-opts)
        places (jdbc/execute! conn
                 (sql/format {:select cols
                              :from [:places]
                              :where user-id-where-clause})
                 jdbc-opts)
        projects (jdbc/execute! conn
                   (sql/format {:select cols
                                :from [:projects]
                                :where user-id-where-clause})
                   jdbc-opts)
        goals (jdbc/execute! conn
                (sql/format {:select cols
                              :from [:goals]
                              :where user-id-where-clause})
                jdbc-opts)
        to-map (fn [items] (into {} (map (fn [i] [(:id i) (select-keys i [:name :badge_title])]) items)))]
    {:people-by-id (to-map people)
     :places-by-id (to-map places)
     :projects-by-id (to-map projects)
     :goals-by-id (to-map goals)}))

(defn build-importance-clause [importance]
  (case importance
    "important" [:in :importance ["important" "critical"]]
    "critical" [:= :importance "critical"]
    nil))

(defn build-urgency-clause [urgency]
  (case urgency
    "urgent" [:in :urgency ["urgent" "superurgent"]]
    "superurgent" [:= :urgency "superurgent"]
    nil))

(defn build-date-range-clause [field date-from date-to]
  (let [clauses (cond-> []
                  date-from (conj [:>= field date-from])
                  date-to (conj [:< field date-to]))]
    (when (seq clauses)
      (into [:and] clauses))))

(defn build-scope-clause [context strict]
  (when context
    (if strict
      [:= :scope context]
      (case context
        "private" [:in :scope ["private" "both"]]
        "work" [:in :scope ["work" "both"]]
        nil))))

(def valid-category-types #{"person" "place" "project" "goal"})

(defn validate-category-type! [category-type]
  (when-not (contains? valid-category-types category-type)
    (throw (ex-info "Invalid category type" {:category-type category-type}))))

(defn category-owned-by-user? [ds category-type category-id user-id]
  (let [table-name (case category-type
                     "person" :people
                     "place" :places
                     "project" :projects
                     "goal" :goals)]
    (some? (jdbc/execute-one! (get-conn ds)
             (sql/format {:select [:id]
                          :from [table-name]
                          :where [:and [:= :id category-id] (user-id-where-clause user-id)]})
             jdbc-opts))))

(defn- query-categories-chunked [conn task-ids]
  (if (empty? task-ids)
    []
    (let [chunk-size 500
          chunks (partition-all chunk-size task-ids)]
      (mapcat (fn [chunk]
                (jdbc/execute! conn
                  (sql/format {:select [:task_id :category_type :category_id]
                               :from [:task_categories]
                               :where [:in :task_id (vec chunk)]})
                  jdbc-opts))
              chunks))))

(defn- normalize-task [task]
  (-> task
      (update :description #(or % ""))
      (update :sort_order #(or % 0.0))
      (update :due_time #(when (and % (not= % "")) %))))

(defn- associate-categories-with-resources-for-export [resources categories-by-resource people-by-id projects-by-id]
  (mapv (fn [resource]
          (let [resource-categories (get categories-by-resource (:id resource) [])]
            (assoc resource
                   :people (extract-category resource-categories "person" people-by-id)
                   :projects (extract-category resource-categories "project" projects-by-id))))
        resources))

(defn export-all-data [ds user-id]
  (let [conn (get-conn ds)
        user-where (user-id-where-clause user-id)
        tasks (jdbc/execute! conn
                (sql/format {:select task-select-columns
                             :from [:tasks]
                             :where user-where
                             :order-by [[:created_at :asc]]})
                jdbc-opts)
        task-ids (mapv :id tasks)
        categories (query-categories-chunked conn task-ids)
        people (jdbc/execute! conn
                 (sql/format {:select [:id :name :description :sort_order :badge_title]
                              :from [:people]
                              :where user-where
                              :order-by [[:sort_order :asc] [:name :asc]]})
                 jdbc-opts)
        places (jdbc/execute! conn
                 (sql/format {:select [:id :name :description :sort_order :badge_title]
                              :from [:places]
                              :where user-where
                              :order-by [[:sort_order :asc] [:name :asc]]})
                 jdbc-opts)
        projects (jdbc/execute! conn
                   (sql/format {:select [:id :name :description :sort_order :badge_title]
                                :from [:projects]
                                :where user-where
                                :order-by [[:sort_order :asc] [:name :asc]]})
                   jdbc-opts)
        goals (jdbc/execute! conn
                (sql/format {:select [:id :name :description :sort_order :badge_title]
                             :from [:goals]
                             :where user-where
                             :order-by [[:sort_order :asc] [:name :asc]]})
                jdbc-opts)
        resources (jdbc/execute! conn
                    (sql/format {:select [:id :title :link :description :tags :created_at :modified_at :sort_order :scope :importance]
                                 :from [:resources]
                                 :where user-where
                                 :order-by [[:created_at :asc]]})
                    jdbc-opts)
        resource-ids (mapv :id resources)
        resource-categories (when (seq resource-ids)
                              (jdbc/execute! conn
                                (sql/format {:select [:resource_id :category_type :category_id]
                                             :from [:resource_categories]
                                             :where [:in :resource_id resource-ids]})
                                jdbc-opts))
        people-by-id (into {} (map (juxt :id :name) people))
        places-by-id (into {} (map (juxt :id :name) places))
        projects-by-id (into {} (map (juxt :id :name) projects))
        goals-by-id (into {} (map (juxt :id :name) goals))
        categories-by-task (group-by :task_id categories)
        categories-by-resource (group-by :resource_id resource-categories)
        tasks-with-categories (->> (associate-categories-with-tasks tasks categories-by-task people-by-id places-by-id projects-by-id goals-by-id)
                                   (mapv normalize-task))
        resources-with-categories (associate-categories-with-resources-for-export resources categories-by-resource people-by-id projects-by-id)
        meet-ids (mapv :id (jdbc/execute! conn (sql/format {:select [:id] :from [:meets] :where user-where}) jdbc-opts))
        all-item-ids {"tsk" task-ids "res" resource-ids "met" meet-ids}
        relations (vec (for [[source-type ids] all-item-ids
                             :when (seq ids)
                             rel (jdbc/execute! conn
                                   (sql/format {:select [:source_type :source_id :target_type :target_id]
                                                :from [:relations]
                                                :where [:and
                                                        [:= :source_type source-type]
                                                        [:in :source_id ids]]})
                                   jdbc-opts)]
                         rel))]
    {:tasks tasks-with-categories
     :people people
     :places places
     :projects projects
     :goals goals
     :resources resources-with-categories
     :relations relations}))

(defn reset-all-data! [ds]
  (let [conn (get-conn ds)]
    (doseq [table [:relations :task_categories :resource_categories :issue_categories :meet_categories :meeting_series_categories :recurring_task_categories :journal_entry_categories :journal_categories :tasks :messages :resources :issues :meets :meeting_series :recurring_tasks :journal_entries :journals :mottos :people :places :projects :goals :users]]
      (jdbc/execute-one! conn (sql/format {:delete-from table})))
    (jdbc/execute-one! conn
      (sql/format {:insert-into :users
                   :values [{:username "e2e-user"
                             :password_hash (hashers/derive "testpass")
                             :has_mail 1}]})
      jdbc-opts)))
