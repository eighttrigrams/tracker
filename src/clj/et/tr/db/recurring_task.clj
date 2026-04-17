(ns et.tr.db.recurring-task
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [clojure.string :as str]
            [et.tr.db :as db]
            [et.tr.scheduling :as scheduling])
  (:import [java.time LocalDate]))

(defn add-recurring-task
  ([ds user-id title] (add-recurring-task ds user-id title "both"))
  ([ds user-id title scope]
   (let [conn (db/get-conn ds)
         valid-scope (db/normalize-scope scope)
         min-order (or (:min_order (jdbc/execute-one! conn
                                     (sql/format {:select [[[:min :sort_order] :min_order]]
                                                  :from [:recurring_tasks]
                                                  :where (db/user-id-where-clause user-id)})
                                     db/jdbc-opts))
                       1.0)
         new-order (- min-order 1.0)
         result (jdbc/execute-one! conn
                  (sql/format {:insert-into :recurring_tasks
                               :values [{:title title
                                         :sort_order new-order
                                         :user_id user-id
                                         :modified_at [:raw "datetime('now')"]
                                         :scope valid-scope}]
                               :returning db/recurring-task-select-columns})
                  db/jdbc-opts)]
     (tel/log! {:level :info :data {:recurring-task-id (:id result) :user-id user-id}} "Recurring task added")
     (assoc result :people [] :places [] :projects [] :goals []))))

(defn- build-recurring-task-category-clauses [categories]
  (let [people-clause (db/build-category-subquery :recurring_task_categories :recurring_task_id :recurring_tasks "person" (:people categories))
        places-clause (db/build-category-subquery :recurring_task_categories :recurring_task_id :recurring_tasks "place" (:places categories))
        projects-clause (db/build-category-subquery :recurring_task_categories :recurring_task_id :recurring_tasks "project" (:projects categories))
        goals-clause (db/build-category-subquery :recurring_task_categories :recurring_task_id :recurring_tasks "goal" (:goals categories))]
    (filterv some? [people-clause places-clause projects-clause goals-clause])))

(defn- associate-categories-with-recurring-tasks [rtasks categories-by-rtask people-by-id places-by-id projects-by-id goals-by-id]
  (mapv (fn [rt]
          (let [rt-categories (get categories-by-rtask (:id rt) [])]
            (assoc rt
                   :people (db/extract-category rt-categories "person" people-by-id)
                   :places (db/extract-category rt-categories "place" places-by-id)
                   :projects (db/extract-category rt-categories "project" projects-by-id)
                   :goals (db/extract-category rt-categories "goal" goals-by-id))))
        rtasks))

(defn list-recurring-tasks
  ([ds user-id] (list-recurring-tasks ds user-id {}))
  ([ds user-id opts]
   (let [{:keys [search-term context strict categories]} opts
         conn (db/get-conn ds)
         user-where (db/user-id-where-clause user-id)
         search-clause (db/build-search-clause search-term [:title :tags])
         scope-clause (db/build-scope-clause context strict)
         category-clauses (build-recurring-task-category-clauses categories)
         where-clause (into [:and user-where]
                            (concat (filter some? [search-clause scope-clause])
                                    category-clauses))
         today-expr [:raw "date('now','localtime')"]
         has-today-task-due {:select [1]
                             :from [:tasks]
                             :where [:and
                                     [:= :tasks.recurring_task_id :recurring_tasks.id]
                                     [:= :tasks.due_date today-expr]
                                     [:= :tasks.done 0]]
                             :limit 1}
         has-future-task-due {:select [1]
                              :from [:tasks]
                              :where [:and
                                      [:= :tasks.recurring_task_id :recurring_tasks.id]
                                      [:> :tasks.due_date today-expr]
                                      [:= :tasks.done 0]]
                              :limit 1}
         has-active-today-task {:select [1]
                                :from [:tasks]
                                :where [:and
                                        [:= :tasks.recurring_task_id :recurring_tasks.id]
                                        [:= :tasks.today 1]
                                        [:= :tasks.done 0]]
                                :limit 1}
         has-archived-today-task {:select [1]
                                  :from [:tasks]
                                  :where [:and
                                          [:= :tasks.recurring_task_id :recurring_tasks.id]
                                          [:= :tasks.today 1]
                                          [:= :tasks.done 1]
                                          [:= [:raw "date(tasks.modified_at,'localtime')"] today-expr]]
                                  :limit 1}
         has-today-task-expr [:case
                              [:= :recurring_tasks.task_type "today"]
                              [:or [:exists has-active-today-task] [:exists has-archived-today-task]]
                              :else
                              [:exists has-today-task-due]]
         has-future-task-expr [:case
                               [:= :recurring_tasks.task_type "today"]
                               [:or [:exists has-active-today-task] [:exists has-archived-today-task]]
                               :else
                               [:exists has-future-task-due]]
         rtasks (jdbc/execute! conn
                  (sql/format {:select (into db/recurring-task-select-columns
                                             [[has-today-task-expr :has_today_task]
                                              [has-future-task-expr :has_future_task]])
                               :from [:recurring_tasks]
                               :where where-clause
                               :order-by [[:sort_order :asc]]})
                  db/jdbc-opts)
         rtasks (mapv (fn [rt]
                        (-> rt
                            (update :has_today_task #(= 1 %))
                            (update :has_future_task #(= 1 %))))
                      rtasks)
         rtask-ids (mapv :id rtasks)
         categories-data (when (seq rtask-ids)
                           (jdbc/execute! conn
                             (sql/format {:select [:recurring_task_id :category_type :category_id]
                                          :from [:recurring_task_categories]
                                          :where [:in :recurring_task_id rtask-ids]})
                             db/jdbc-opts))
         {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
         categories-by-rtask (group-by :recurring_task_id categories-data)]
     (associate-categories-with-recurring-tasks rtasks categories-by-rtask people-by-id places-by-id projects-by-id goals-by-id))))

(defn recurring-task-owned-by-user? [ds rtask-id user-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id]
                        :from [:recurring_tasks]
                        :where [:and [:= :id rtask-id] (db/user-id-where-clause user-id)]})
           db/jdbc-opts)))

(defn get-recurring-task [ds user-id rtask-id]
  (let [conn (db/get-conn ds)
        user-where (db/user-id-where-clause user-id)
        rtask (jdbc/execute-one! conn
                (sql/format {:select db/recurring-task-select-columns
                             :from [:recurring_tasks]
                             :where [:and [:= :id rtask-id] user-where]})
                db/jdbc-opts)]
    (when rtask
      (let [categories-data (jdbc/execute! conn
                              (sql/format {:select [:recurring_task_id :category_type :category_id]
                                           :from [:recurring_task_categories]
                                           :where [:= :recurring_task_id rtask-id]})
                              db/jdbc-opts)
            {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
            categories-by-rtask (group-by :recurring_task_id categories-data)]
        (first (associate-categories-with-recurring-tasks [rtask] categories-by-rtask people-by-id places-by-id projects-by-id goals-by-id))))))

(defn update-recurring-task [ds user-id rtask-id fields]
  (let [set-map (assoc fields :modified_at [:raw "datetime('now')"])
        return-cols (into [:id :created_at :modified_at] (keys fields))]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :recurring_tasks
                   :set set-map
                   :where [:and [:= :id rtask-id] (db/user-id-where-clause user-id)]
                   :returning return-cols})
      db/jdbc-opts)))

(defn delete-recurring-task [ds user-id rtask-id]
  (when (recurring-task-owned-by-user? ds rtask-id user-id)
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:update :tasks
                       :set {:recurring_task_id nil}
                       :where [:= :recurring_task_id rtask-id]}))
        (jdbc/execute-one! tx
          (sql/format {:delete-from :recurring_task_categories
                       :where [:= :recurring_task_id rtask-id]}))
        (let [result (jdbc/execute-one! tx
                       (sql/format {:delete-from :recurring_tasks
                                    :where [:= :id rtask-id]}))]
          (tel/log! {:level :info :data {:recurring-task-id rtask-id :user-id user-id}} "Recurring task deleted")
          {:success (pos? (:next.jdbc/update-count result))})))))

(defn set-recurring-task-field [ds user-id rtask-id field value]
  (let [normalize-fn (get db/field-normalizers field identity)
        valid-value (normalize-fn value)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :recurring_tasks
                   :set {field valid-value
                         :modified_at [:raw "datetime('now')"]}
                   :where [:and [:= :id rtask-id] (db/user-id-where-clause user-id)]
                   :returning [:id field :modified_at]})
      db/jdbc-opts)))

(defn set-recurring-task-schedule [ds user-id rtask-id schedule-days schedule-time schedule-mode biweekly-offset task-type]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :recurring_tasks
                 :set {:schedule_days (or schedule-days "")
                       :schedule_time schedule-time
                       :schedule_mode (or schedule-mode "weekly")
                       :biweekly_offset (if biweekly-offset 1 0)
                       :task_type (or task-type "due_date")
                       :modified_at [:raw "datetime('now')"]}
                 :where [:and [:= :id rtask-id] (db/user-id-where-clause user-id)]
                 :returning [:id :schedule_days :schedule_time :schedule_mode :biweekly_offset :task_type :modified_at]})
    db/jdbc-opts))

(defn create-task-for-recurring [ds user-id rtask-id date time]
  (when (recurring-task-owned-by-user? ds rtask-id user-id)
    (let [conn (db/get-conn ds)
          rtask (jdbc/execute-one! conn
                  (sql/format {:select [:title :scope :task_type]
                               :from [:recurring_tasks]
                               :where [:= :id rtask-id]})
                  db/jdbc-opts)]
      (when rtask
        (jdbc/with-transaction [tx conn]
          (let [min-order (or (:min_order (jdbc/execute-one! tx
                                            (sql/format {:select [[[:min :sort_order] :min_order]]
                                                         :from [:tasks]
                                                         :where (db/user-id-where-clause user-id)})
                                            db/jdbc-opts))
                              1.0)
                new-order (- min-order 1.0)
                today-type? (= "today" (:task_type rtask))
                today-str (str (java.time.LocalDate/now))
                is-today? (= date today-str)
                task-values (cond-> {:title (:title rtask)
                                     :sort_order new-order
                                     :user_id user-id
                                     :modified_at [:raw "datetime('now')"]
                                     :scope (:scope rtask)
                                     :recurring_task_id rtask-id}
                              (and today-type? is-today?) (assoc :today 1)
                              (and today-type? (not is-today?)) (assoc :lined_up_for date)
                              (not today-type?) (assoc :due_date date :due_time time))
                task (jdbc/execute-one! tx
                       (sql/format {:insert-into :tasks
                                    :values [task-values]
                                    :returning db/task-select-columns})
                       db/jdbc-opts)
                rtask-cats (jdbc/execute! tx
                             (sql/format {:select [:category_type :category_id]
                                          :from [:recurring_task_categories]
                                          :where [:= :recurring_task_id rtask-id]})
                             db/jdbc-opts)]
            (doseq [{:keys [category_type category_id]} rtask-cats]
              (jdbc/execute-one! tx
                (sql/format {:insert-into :task_categories
                             :values [{:task_id (:id task)
                                       :category_type category_type
                                       :category_id category_id}]
                             :on-conflict []
                             :do-nothing true})))
            (tel/log! {:level :info :data {:task-id (:id task) :recurring-task-id rtask-id :user-id user-id}} "Task created from recurring task")
            (assoc task :people [] :places [] :projects [] :goals [])))))))

(defn categorize-recurring-task [ds user-id rtask-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (recurring-task-owned-by-user? ds rtask-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:insert-into :recurring_task_categories
                       :values [{:recurring_task_id rtask-id
                                 :category_type category-type
                                 :category_id category-id}]
                       :on-conflict []
                       :do-nothing true}))
        (jdbc/execute-one! tx
          (sql/format {:update :recurring_tasks
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id rtask-id]}))))))

(defn uncategorize-recurring-task [ds user-id rtask-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (recurring-task-owned-by-user? ds rtask-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :recurring_task_categories
                       :where [:and
                               [:= :recurring_task_id rtask-id]
                               [:= :category_type category-type]
                               [:= :category_id category-id]]}))
        (jdbc/execute-one! tx
          (sql/format {:update :recurring_tasks
                       :set {:modified_at [:raw "datetime('now')"]}
                       :where [:= :id rtask-id]}))))))

(defn get-taken-dates [ds user-id rtask-id]
  (when (recurring-task-owned-by-user? ds rtask-id user-id)
    (let [conn (db/get-conn ds)
          rtask (jdbc/execute-one! conn
                  (sql/format {:select [:task_type]
                               :from [:recurring_tasks]
                               :where [:= :id rtask-id]})
                  db/jdbc-opts)
          today-type? (= "today" (:task_type rtask))
          rows (if today-type?
                 (let [lined-up (jdbc/execute! conn
                                  (sql/format {:select-distinct [:lined_up_for]
                                               :from [:tasks]
                                               :where [:and
                                                       [:= :recurring_task_id rtask-id]
                                                       [:!= :lined_up_for nil]]})
                                  db/jdbc-opts)
                       has-today? (some? (jdbc/execute-one! conn
                                           (sql/format {:select [1]
                                                        :from [:tasks]
                                                        :where [:and
                                                                [:= :recurring_task_id rtask-id]
                                                                [:= :today 1]
                                                                [:= :done 0]]
                                                        :limit 1})
                                           db/jdbc-opts))]
                   (cond-> (mapv :lined_up_for lined-up)
                     has-today? (conj (str (java.time.LocalDate/now)))))
                 (jdbc/execute! conn
                   (sql/format {:select-distinct [:due_date]
                                :from [:tasks]
                                :where [:and
                                        [:= :recurring_task_id rtask-id]
                                        [:!= :due_date nil]]})
                   db/jdbc-opts))]
      (if today-type?
        rows
        (mapv :due_date rows)))))

(defn- query-existing-due-dates [conn rtask-id today-expr]
  (set (map :due_date
         (jdbc/execute! conn
           (sql/format {:select-distinct [:due_date]
                        :from [:tasks]
                        :where [:and
                                [:= :recurring_task_id rtask-id]
                                [:!= :due_date nil]
                                [:>= :due_date today-expr]]})
           db/jdbc-opts))))

(defn- query-today-type-state [conn rtask-id today-expr]
  (let [has-active? (some? (jdbc/execute-one! conn
                             (sql/format {:select [1]
                                          :from [:tasks]
                                          :where [:and
                                                  [:= :recurring_task_id rtask-id]
                                                  [:or [:= :today 1]
                                                       [:and [:!= :lined_up_for nil]
                                                             [:>= :lined_up_for today-expr]]]
                                                  [:= :done 0]]
                                          :limit 1})
                             db/jdbc-opts))
        done-today? (some? (jdbc/execute-one! conn
                             (sql/format {:select [1]
                                          :from [:tasks]
                                          :where [:and
                                                  [:= :recurring_task_id rtask-id]
                                                  [:= :done 1]
                                                  [:= [:raw "date(tasks.done_at,'localtime')"] today-expr]]
                                          :limit 1})
                             db/jdbc-opts))]
    {:has-active? has-active? :done-today? done-today?}))

(defn auto-create-tasks
  ([ds user-id] (auto-create-tasks ds user-id {}))
  ([ds user-id _opts]
   (let [conn (db/get-conn ds)
         today-expr [:raw "date('now','localtime')"]
         all-rtasks (jdbc/execute! conn
                      (sql/format {:select [:id :schedule_days :schedule_time :schedule_mode :biweekly_offset :task_type]
                                   :from [:recurring_tasks]
                                   :where [:and
                                           (db/user-id-where-clause user-id)
                                           [:!= :schedule_days ""]]})
                      db/jdbc-opts)
         today (LocalDate/now)
         today-str (str today)
         created (atom [])]
     (doseq [{:keys [id schedule_days schedule_time schedule_mode biweekly_offset task_type]} all-rtasks]
       (let [mode (or schedule_mode "weekly")
             schedule-days-set (set (str/split schedule_days #","))
             today-type? (= "today" task_type)
             scheduled-dates (scheduling/scheduled-dates-from mode schedule-days-set biweekly_offset today 10)
             dates-to-create (if today-type?
                               (let [{:keys [has-active? done-today?]} (query-today-type-state conn id today-expr)]
                                 (when-let [d (scheduling/next-item-to-create today-str scheduled-dates has-active? done-today?)]
                                   [d]))
                               (let [existing (query-existing-due-dates conn id today-expr)]
                                 (scheduling/items-to-create today-str scheduled-dates existing)))]
         (doseq [date dates-to-create]
           (let [d (LocalDate/parse date)
                 day-num (.getValue (.getDayOfWeek d))
                 time (when-not today-type?
                        (scheduling/get-schedule-time-for-day schedule_time day-num))]
             (when-let [task (create-task-for-recurring ds user-id id date time)]
               (swap! created conj task))))))
     @created)))
