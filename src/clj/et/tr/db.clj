(ns et.tr.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [et.tr.migrations :as migrations]
            [et.tr.ordering :as ordering]
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

(def category-groups
  "The six Category Groups, in the order the owner asked for them: People,
  Places, Workstreams, Projects, Goals, Assets.

  Since 073-unify-category-tables they all live in one `categories` table and
  a group is just a value of its `category_type` column, so adding a group is
  adding an entry here rather than adding a table. `:type` is that stored
  value (and the value mirrored in the eight <entity>_categories join tables);
  `:key` is the plural keyword the HTTP API, the ordering contexts and the
  app-state use for the same group.

  The UI calls this concept a Group. The code calls it category_type because
  that spelling predates the unification and is already everywhere; see the
  schema comment on categories.category_type."
  [{:type "person"     :key :people}
   {:type "place"      :key :places}
   {:type "workstream" :key :workstreams}
   {:type "project"    :key :projects}
   {:type "goal"       :key :goals}
   {:type "asset"      :key :assets}])

(def category-type->key (into {} (map (juxt :type :key)) category-groups))
(def category-key->type (into {} (map (juxt :key :type)) category-groups))
(def category-type-order (mapv :type category-groups))
(def category-key-order (mapv :key category-groups))
(def valid-category-types (set category-type-order))

(def categorizable-entities
  "Every kind of Item that can carry Categories — one entry per
  `<kind>_categories` join table, which is the same set as the route contexts
  that mount `POST /:id/categorize`.

  The Groups had a registry (`category-groups` above) and the kinds did not, so
  the kinds went on being counted by hand. They were counted wrong twice: the
  work order that fixed Category inheritance said seven add paths and the review
  of it agreed, and both had missed the Journal, whose add form sits under the
  same sidebar as the other seven.

  `:inherits-add-filters?` is whether this kind's client add path gives a new
  Item the Categories its list was filtered by, through
  `et.tr.ui.state.category-filters/apply-filter-categories!`. Where it is false
  the Item is still categorizable — it just does not get its Categories that way,
  and `:why-not` says what it gets them from instead. A kind is not allowed to be
  false without a reason; see et.tr.category-inheritance-coverage-test, which
  holds this list against the routes and against the e2e feature so a ninth kind
  cannot arrive without either a scenario or a stated reason."
  [{:kind :task :segment "tasks" :join-table :task_categories
    :inherits-add-filters? true}
   {:kind :issue :segment "issues" :join-table :issue_categories
    :inherits-add-filters? true}
   {:kind :meet :segment "meets" :join-table :meet_categories
    :inherits-add-filters? true}
   {:kind :meeting-series :segment "meeting-series" :join-table :meeting_series_categories
    :inherits-add-filters? true}
   {:kind :recurring-task :segment "recurring-tasks" :join-table :recurring_task_categories
    :inherits-add-filters? true}
   {:kind :resource :segment "resources" :join-table :resource_categories
    :inherits-add-filters? true}
   {:kind :journal :segment "journals" :join-table :journal_categories
    :inherits-add-filters? true}
   {:kind :journal-entry :segment "journal-entries" :join-table :journal_entry_categories
    :inherits-add-filters? false
    :why-not (str "A journal entry is not added from a filter-bearing form. The only way to "
                  "make one is POST /api/journals/:id/create-entry — a date on a particular "
                  "journal, no title of its own — and that copies the journal's own Categories "
                  "into the entry inside the same transaction. Its filing comes from its "
                  "parent, which is a stronger claim than the sidebar's, and giving it the "
                  "sidebar's as well would file it under two answers at once.")}])

(def categorizable-join-tables (mapv :join-table categorizable-entities))

(defn validate-category-type! [category-type]
  (when-not (contains? valid-category-types category-type)
    (throw (ex-info "Invalid category type" {:category-type category-type}))))

(defn category-type-where
  "WHERE fragment restricting a `categories` query to one group."
  [category-type]
  [:= :category_type category-type])

(def valid-scopes #{"private" "both" "work"})

(defn normalize-scope [scope]
  (if (contains? valid-scopes scope) scope "both"))

(def valid-importances #{"normal" "important" "critical"})

(defn- normalize-importance [importance]
  (if (contains? valid-importances importance) importance "normal"))

(def valid-urgencies #{"default" "urgent" "superurgent"})

(def urgent-urgencies
  "The urgencies Urgent Matters renders, i.e. membership of that ordering
  context."
  #{"urgent" "superurgent"})

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

(def task-select-columns (into [:id :title :description :tags :created_at :modified_at :due_date :due_time]
                               (concat (map ordering/column [:tasks-page :tasks-day-list :tasks-urgent])
                                       [:done :done_at :scope :importance :urgency :today :lined_up_for :maybe :recurring_task_id :issue_id :reminder :reminder_date :relation_badge_title])))

(def resource-select-columns (into [:id :title :link :description :tags :created_at :modified_at]
                                   (cons (ordering/column :resources-page)
                                         [:scope :importance :relation_badge_title])))

(def issue-select-columns (into [:id :title :description :tags :created_at :modified_at]
                                (concat (map ordering/column [:issues-page :issues-urgent])
                                        [:scope :importance :urgency :resolved :resolved_at :relation_badge_title])))

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

(defn top-of-order
  "The value that puts a row first in `context` among the caller's rows that
  `extra-where` selects — the same step-below-the-minimum scheme the add-*
  functions use. Without an `extra-where`, first among all of them.

  `ds` may be a transaction: get-conn passes a Connection through, so a caller
  that has to compute this inside its own transaction can still use this rather
  than writing the query out again."
  ([ds context user-id] (top-of-order ds context user-id nil))
  ([ds context user-id extra-where]
   (- (or (:min_order (jdbc/execute-one! (get-conn ds)
                        (sql/format {:select [[[:min (ordering/column context)] :min_order]]
                                     :from [(ordering/table context)]
                                     :where (if extra-where
                                              [:and (user-id-where-clause user-id) extra-where]
                                              (user-id-where-clause user-id))})
                        jdbc-opts))
         1.0)
      1.0)))

(defn write-order!
  "Write `value` into the column `context` owns, for the caller's row `id`. The
  one place an ordering column is assigned, so nothing else has to know which
  column belongs to which context."
  [ds context user-id id value]
  (jdbc/execute-one! (get-conn ds)
    (sql/format {:update (ordering/table context)
                 :set {(ordering/column context) value}
                 :where [:and [:= :id id] (user-id-where-clause user-id)]}))
  {:success true (ordering/column context) value})

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

(def empty-category-groups
  "Every Category Group key mapped to [], for freshly created rows that cannot
  have categories yet. One map instead of a hand-written
  `:people [] :places [] ...` per entity namespace, so a new group reaches all
  of them at once."
  (into {} (map (fn [{:keys [key]}] [key []])) category-groups))

(defn lookup-for-group
  "The id -> {:name :badge_title} map for one group out of fetch-category-lookups."
  [lookups group-key]
  (get lookups (keyword (str (name group-key) "-by-id")) {}))

(defn assoc-category-groups
  "Decorate each row with one key per Category Group (:people, :places,
  :workstreams, :projects, :goals, :assets), read out of that row's join rows in
  `categories-by-entity` and named through `lookups` (the map
  fetch-category-lookups returns).

  Every entity that can carry categories decorates its rows the same way, so
  they all share this rather than each spelling out one line per group."
  [rows categories-by-entity lookups]
  (mapv (fn [row]
          (let [row-categories (get categories-by-entity (:id row) [])]
            (reduce (fn [r {:keys [type key]}]
                      (assoc r key (extract-category row-categories type
                                                     (lookup-for-group lookups key))))
                    row
                    category-groups)))
        rows))

(defn associate-categories-with-tasks [tasks categories-by-task lookups]
  (assoc-category-groups tasks categories-by-task lookups))

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
     (let [entity-ref-id (keyword (str (name entity-ref) ".id"))]
       [:exists {:select [1]
                 :from [join-table]
                 :join [[:categories] [:= :categories.id (keyword (str (name join-table) ".category_id"))]]
                 :where [:and
                         [:= (keyword (str (name join-table) "." (name entity-id-col))) entity-ref-id]
                         [:= (keyword (str (name join-table) ".category_type")) category-type]
                         [:= :categories.category_type category-type]
                         [:in :categories.name category-names]]}]))))

(defn build-category-clauses
  "One category subquery per Category Group named in `categories` (a
  {:people [name...] :workstreams [...] ...} filter map), ANDed by the caller.
  Groups the filter says nothing about contribute no clause."
  [join-table entity-id-col entity-ref categories]
  (filterv some?
           (map (fn [{:keys [type key]}]
                  (build-category-subquery join-table entity-id-col entity-ref
                                           type (get categories key)))
                category-groups)))

(declare build-scope-clause)

(defn fetch-category-lookups
  "Build {:people-by-id :places-by-id :workstreams-by-id :projects-by-id
  :goals-by-id :assets-by-id} maps of category-id -> {:name :badge_title},
  used to decorate entity rows with their category badges. When opts carries a
  :context (with optional :strict), the lookups are restricted to categories
  matching that scope, so out-of-scope category badges never reach the entity
  cards. Callers that must see every category (e.g. single-item edit fetches)
  omit opts.

  One query over the unified table now, grouped by category_type, rather than
  one query per group."
  ([conn user-id-where-clause] (fetch-category-lookups conn user-id-where-clause nil))
  ([conn user-id-where-clause {:keys [context strict]}]
   (let [scope-clause (build-scope-clause context strict)
         where (if scope-clause [:and user-id-where-clause scope-clause] user-id-where-clause)
         rows (jdbc/execute! conn
                (sql/format {:select [:id :name :badge_title :category_type]
                             :from [:categories]
                             :where where})
                jdbc-opts)
         by-type (group-by :category_type rows)]
     (into {}
           (map (fn [{:keys [type key]}]
                  [(keyword (str (name key) "-by-id"))
                   (into {} (map (fn [i] [(:id i) (select-keys i [:name :badge_title])]))
                         (get by-type type []))]))
           category-groups))))

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

(defn category-owned-by-user? [ds category-type category-id user-id]
  (some? (jdbc/execute-one! (get-conn ds)
           (sql/format {:select [:id]
                        :from [:categories]
                        :where [:and [:= :id category-id]
                                (category-type-where category-type)
                                (user-id-where-clause user-id)]})
           jdbc-opts)))

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

(defn- associate-categories-with-resources-for-export [resources categories-by-resource lookups]
  (mapv (fn [resource]
          (let [resource-categories (get categories-by-resource (:id resource) [])]
            (reduce (fn [r {:keys [type key]}]
                      (assoc r key (extract-category
                                    resource-categories type
                                    (get lookups (keyword (str (name key) "-by-id")) {}))))
                    resource
                    category-groups)))
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
        category-rows (jdbc/execute! conn
                        (sql/format {:select [:id :name :description :sort_order :badge_title :category_type]
                                     :from [:categories]
                                     :where user-where
                                     :order-by [[:sort_order :asc] [:name :asc]]})
                        jdbc-opts)
        categories-by-group (group-by :category_type category-rows)
        ;; {:people [...] :places [...] ... :assets [...]}, each row without the
        ;; category_type column the group key already carries.
        groups (into {}
                     (map (fn [{:keys [type key]}]
                            [key (mapv #(dissoc % :category_type)
                                       (get categories-by-group type []))]))
                     category-groups)
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
        ;; extract-category reads :name/:badge_title off each looked-up entry,
        ;; so the lookups have to be maps, not bare name strings. They used to
        ;; be built here as id -> name, which made every exported category read
        ;; {:name nil :badge_title nil}; fetch-category-lookups returns the
        ;; shape extract-category actually wants.
        lookups (fetch-category-lookups conn user-where)
        categories-by-task (group-by :task_id categories)
        categories-by-resource (group-by :resource_id resource-categories)
        tasks-with-categories (->> (associate-categories-with-tasks tasks categories-by-task lookups)
                                   (mapv normalize-task))
        resources-with-categories (associate-categories-with-resources-for-export resources categories-by-resource lookups)
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
    (merge groups
           {:tasks tasks-with-categories
            :resources resources-with-categories
            :relations relations})))

(defn reset-all-data! [ds]
  (let [conn (get-conn ds)
        ;; The join tables come from categorizable-entities rather than being
        ;; listed again: this used to be the second hand-written enumeration of
        ;; the eight, and a ninth kind added here and forgotten there would have
        ;; leaked its rows from one e2e scenario into the next.
        tables (concat [:relations :working_on]
                       categorizable-join-tables
                       [:tasks :messages :resources :issues :meets :meeting_series
                        :recurring_tasks :journal_entries :journals :mottos :categories :users])]
    (doseq [table tables]
      (jdbc/execute-one! conn (sql/format {:delete-from table})))
    (jdbc/execute-one! conn
      (sql/format {:insert-into :users
                   :values [{:username "e2e-user"
                             :password_hash (hashers/derive "testpass")
                             :has_mail 1}]})
      jdbc-opts)))
