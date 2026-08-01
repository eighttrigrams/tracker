(ns et.tr.db.task
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.tr.clock :as clock]
            [et.tr.db :as db]
            [et.tr.ordering :as ordering]
            [et.tr.day-order :as day-order]
            [et.tr.db.day-list :as db.day-list]
            [et.tr.db.recurring-task :as db.recurring-task]
            [et.tr.db.category-rule :as db.category-rule]
            [et.tr.db.category-exclusion :as db.category-exclusion]
            [et.tr.db.relation :as relation]
            [et.tr.db.working-on :as db.working-on]))

(defn add-task
  ([ds user-id title] (add-task ds user-id title "both"))
  ([ds user-id title scope] (add-task ds user-id title scope nil))
  ([ds user-id title scope importance]
   (let [conn (db/get-conn ds)
         valid-scope (db/normalize-scope scope)
         min-order (or (:min_order (jdbc/execute-one! conn
                                     (sql/format {:select [[[:min :sort_order] :min_order]]
                                                  :from [:tasks]
                                                  :where (db/user-id-where-clause user-id)})
                                     db/jdbc-opts))
                       1.0)
         new-order (- min-order 1.0)
         values (cond-> {:title title
                         :sort_order new-order
                         :user_id user-id
                         :modified_at (clock/sql-now)
                         :scope valid-scope}
                  (contains? db/valid-importances importance) (assoc :importance importance))
         result (jdbc/execute-one! conn
                  (sql/format {:insert-into :tasks
                               :values [values]
                               :returning (conj db/task-select-columns :user_id)})
                  db/jdbc-opts)]
     (tel/log! {:level :info :data {:task-id (:id result) :user-id user-id}} "Task added")
     result)))

(defn- build-category-clauses [categories]
  (let [people-clause (db/build-category-subquery "person" (:people categories))
        places-clause (db/build-category-subquery "place" (:places categories))
        projects-clause (db/build-category-subquery "project" (:projects categories))
        goals-clause (db/build-category-subquery "goal" (:goals categories))]
    (filterv some? [people-clause places-clause projects-clause goals-clause])))

(defn list-tasks
  ([ds user-id] (list-tasks ds user-id :recent))
  ([ds user-id sort-mode] (list-tasks ds user-id sort-mode nil))
  ([ds user-id sort-mode opts]
   (let [opts (if (string? opts) {:search-term opts} opts)
         {:keys [search-term importance context strict categories excluded-categories recurring-task-id issue-id limit date-from date-to]} opts
         conn (db/get-conn ds)
         user-where (db/user-id-where-clause user-id)
         base-where (cond
                      ;; The focused-issue task listing shows every task belonging
                      ;; to the issue (done included), like a parent's child list.
                      issue-id [:and user-where [:= :issue_id issue-id]]
                      :else
                      (case sort-mode
                      :due-date [:and user-where [:not= :due_date nil] [:= :done 0]]
                      :done [:and user-where [:= :done 1]]
                      :today [:and user-where [:= :done 0]
                              [:or [:not= :due_date nil]
                                   [:in :urgency ["urgent" "superurgent"]]
                                   [:= :today 1]
                                   [:not= :lined_up_for nil]
                                   [:= :reminder "active"]]]
                      :reminder [:and user-where [:= :done 0] [:not= :reminder_date nil]]
                      :unassigned [:and user-where [:= :done 0]
                                   [:= :due_date nil]
                                   [:= :reminder_date nil]
                                   [:= :today 0]
                                   [:= :lined_up_for nil]
                                   [:= :urgency "default"]]
                      [:and user-where [:= :done 0]]))
         search-clause (db/build-search-clause search-term)
         importance-clause (db/build-importance-clause importance)
         scope-clause (db/build-scope-clause context strict)
         category-clauses (build-category-clauses categories)
         recurring-clause (when recurring-task-id [:= :recurring_task_id recurring-task-id])
         date-range-clause (db/build-date-range-clause [:coalesce :done_at :modified_at] date-from date-to)
         exclusion-clauses (db.category-exclusion/build-exclusion-clauses ds user-id excluded-categories)
         where-clause (into [:and base-where]
                            (concat (filter some? [search-clause importance-clause scope-clause recurring-clause date-range-clause])
                                    category-clauses
                                    exclusion-clauses))
         order-by (cond
                    ;; Match the issue's belonging-task ordering: open first, then
                    ;; by manual sort order.
                    issue-id [[:done :asc] [:sort_order :asc] [:id :asc]]
                    :else
                    (case sort-mode
                    :manual [[:sort_order :asc] [:created_at :desc]]
                    :due-date [[:due_date :asc]
                               [[:case [:not= :due_time nil] 1 :else 0] :desc]
                               [:due_time :asc]]
                    :done [[:done_at :desc]]
                    :today [[:due_date :asc]
                            [[:case [:not= :due_time nil] 1 :else 0] :desc]
                            [:due_time :asc]]
                    :reminder [[:reminder_date :asc]]
                    :added [[:created_at :desc] [:id :desc]]
                    :recent [[[:case [:= :urgency "superurgent"] 0
                                     [:= :urgency "urgent"] 1
                                     :else 2] :asc]
                             [:modified_at :desc]]
                    [[:modified_at :desc]]))
         tasks (jdbc/execute! conn
                 (sql/format (cond-> {:select db/task-select-columns
                                      :from [:tasks]
                                      :where where-clause
                                      :order-by order-by}
                               limit (assoc :limit limit)))
                 db/jdbc-opts)
         task-ids (mapv :id tasks)
         categories (when (seq task-ids)
                      (jdbc/execute! conn
                        (sql/format {:select [:task_id :category_type :category_id]
                                     :from [:task_categories]
                                     :where [:in :task_id task-ids]})
                        db/jdbc-opts))
         {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where {:context context :strict strict})
         categories-by-task (group-by :task_id categories)
         tasks-with-categories (db/associate-categories-with-tasks tasks categories-by-task people-by-id places-by-id projects-by-id goals-by-id)]
     (relation/associate-relations-with-items tasks-with-categories "tsk" conn))))

(defn get-task [ds user-id task-id]
  (let [conn (db/get-conn ds)
        user-where (db/user-id-where-clause user-id)
        task (jdbc/execute-one! conn
               (sql/format {:select db/task-select-columns
                            :from [:tasks]
                            :where [:and [:= :id task-id] user-where]})
               db/jdbc-opts)]
    (when task
      (let [categories (jdbc/execute! conn
                         (sql/format {:select [:task_id :category_type :category_id]
                                      :from [:task_categories]
                                      :where [:= :task_id task-id]})
                         db/jdbc-opts)
            {:keys [people-by-id places-by-id projects-by-id goals-by-id]} (db/fetch-category-lookups conn user-where)
            categories-by-task (group-by :task_id categories)]
        (first (db/associate-categories-with-tasks [task] categories-by-task people-by-id places-by-id projects-by-id goals-by-id))))))

(defn task-owned-by-user? [ds task-id user-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id]
                        :from [:tasks]
                        :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]})
           db/jdbc-opts)))

(defn categorize-task [ds user-id task-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (task-owned-by-user? ds task-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)
          closure (db.category-rule/resolve-closure ds user-id [[category-type category-id]])]
      (jdbc/with-transaction [tx conn]
        (let [applied (db.category-rule/apply-closure! tx :task_categories :task_id task-id closure)]
          (jdbc/execute-one! tx
            (sql/format {:update :tasks
                         :set {:modified_at (clock/sql-now)}
                         :where [:= :id task-id]}))
          applied)))))

(defn uncategorize-task [ds user-id task-id category-type category-id]
  (db/validate-category-type! category-type)
  (when (and (task-owned-by-user? ds task-id user-id)
             (db/category-owned-by-user? ds category-type category-id user-id))
    (let [conn (db/get-conn ds)]
      (jdbc/with-transaction [tx conn]
        (jdbc/execute-one! tx
          (sql/format {:delete-from :task_categories
                       :where [:and
                               [:= :task_id task-id]
                               [:= :category_type category-type]
                               [:= :category_id category-id]]}))
        (jdbc/execute-one! tx
          (sql/format {:update :tasks
                       :set {:modified_at (clock/sql-now)}
                       :where [:= :id task-id]}))))))

(defn update-task
  ([ds user-id task-id fields] (update-task ds user-id task-id fields nil))
  ([ds user-id task-id fields expected-modified-at]
   (let [field-names (keys fields)
         set-map (assoc fields :modified_at (clock/sql-now))
         return-cols (into [:id :created_at :modified_at] field-names)]
     (jdbc/execute-one! (db/get-conn ds)
       (sql/format {:update :tasks
                    :set set-map
                    :where (db/update-where task-id user-id expected-modified-at)
                    :returning return-cols})
       db/jdbc-opts))))

(defn get-task-sort-order [ds user-id task-id]
  (:sort_order (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:select [:sort_order]
                              :from [:tasks]
                              :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]})
                 db/jdbc-opts)))

(defn reorder-task [ds user-id task-id new-sort-order]
  (db/write-order! ds :tasks-page user-id task-id new-sort-order))

(defn list-urgent-tasks
  "The caller's open tasks of one urgency, in the order Urgent Matters shows
  them — just the columns its reorder arithmetic needs."
  [ds user-id urgency]
  (let [col (ordering/column :tasks-urgent)]
    (jdbc/execute! (db/get-conn ds)
      (sql/format {:select [:id col]
                   :from [:tasks]
                   :where [:and (db/user-id-where-clause user-id)
                           [:= :done 0]
                           [:= :urgency urgency]]
                   :order-by [[col :asc] [:id :asc]]})
      db/jdbc-opts)))

(defn reorder-task-in-urgent [ds user-id task-id new-order]
  (db/write-order! ds :tasks-urgent user-id task-id new-order))

(defn set-task-sort-order-today [ds user-id task-id new-day-order]
  (db/write-order! ds :tasks-day-list user-id task-id new-day-order))

(defn- day-membership-row [ds user-id task-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select [:id :due_date :today :lined_up_for (ordering/column :tasks-day-list)]
                 :from [:tasks]
                 :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]})
    db/jdbc-opts))

(defn- materialize-day-position!
  "Give a task that has just joined a day list a concrete position at the end of
  it. Nothing derives a day position from another order, so one has to exist by
  the time the task is rendered. The position it held on the day it came from
  means nothing on the new one, so only a task that stayed on the same day keeps
  it — which is what the worker's lined-up-for-today → today promotion is."
  [ds user-id task-id before after]
  (let [today (clock/today-str)
        date (day-order/flagged-date after today)]
    (when (and date
               (not (and ((ordering/column :tasks-day-list) before)
                         (= date (day-order/flagged-date before today)))))
      (set-task-sort-order-today ds user-id task-id (db.day-list/end-position ds user-id date)))))

(defn set-task-due-date [ds user-id task-id due-date]
  ;; A due date change moves the task to another day (or off the day lists
  ;; altogether), which is what sort_order_today is relative to, so the manual day
  ;; position goes with it.
  (let [set-map (if (nil? due-date)
                  (merge {:due_date due-date
                          :due_time nil
                          :modified_at (clock/sql-now)}
                         (ordering/clearing :tasks-day-list))
                  (merge {:due_date due-date
                          :today 0
                          :lined_up_for nil
                          :maybe 0
                          :urgency "default"
                          :modified_at (clock/sql-now)}
                         (ordering/clearing :tasks-day-list)))]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :tasks
                   :set set-map
                   :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]
                   :returning [:id :due_date :due_time :today :lined_up_for :maybe :urgency :modified_at]})
      db/jdbc-opts)))

(defn set-task-due-time [ds user-id task-id due-time]
  (let [normalized-time (if (empty? due-time) nil due-time)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :tasks
                   :set {:due_time normalized-time
                         :modified_at (clock/sql-now)}
                   :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]
                   :returning [:id :due_date :due_time :modified_at]})
      db/jdbc-opts)))

(defn delete-task [ds user-id task-id]
  (when (task-owned-by-user? ds task-id user-id)
    (let [conn (db/get-conn ds)
          {:keys [recurring_task_id today]}
          (jdbc/execute-one! conn
            (sql/format {:select [:recurring_task_id :today]
                         :from [:tasks]
                         :where [:= :id task-id]})
            db/jdbc-opts)
          result (jdbc/with-transaction [tx conn]
                   (jdbc/execute-one! tx
                     (sql/format {:delete-from :task_categories
                                  :where [:= :task_id task-id]}))
                   (db.working-on/clear-if-task! tx user-id task-id)
                   (jdbc/execute-one! tx
                     (sql/format {:delete-from :relations
                                  :where [:or
                                          [:and [:= :source_type "tsk"] [:= :source_id task-id]]
                                          [:and [:= :target_type "tsk"] [:= :target_id task-id]]]}))
                   (let [r (jdbc/execute-one! tx
                             (sql/format {:delete-from :tasks
                                          :where [:= :id task-id]}))]
                     (tel/log! {:level :info :data {:task-id task-id :user-id user-id}} "Task deleted")
                     {:success (pos? (:next.jdbc/update-count r))}))]
      (when (and (:success result) recurring_task_id (= 1 today))
        (db.recurring-task/create-next-after-today-delete ds user-id recurring_task_id))
      result)))

(defn set-task-done [ds user-id task-id done?]
  (let [done-val (if done? 1 0)
        result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:update :tasks
                              :set (cond-> {:done done-val
                                            :modified_at (clock/sql-now)}
                                     done? (-> (assoc :today 0 :lined_up_for nil :maybe 0
                                                      :done_at (clock/sql-now))
                                               (merge (ordering/clearing :tasks-day-list)))
                                     (not done?) (assoc :done_at nil))
                              :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]
                              :returning [:id :done :modified_at :done_at]})
                 db/jdbc-opts)]
    ;; Un-doing does not restore the marker, so only the done? direction clears.
    (when (and done? result)
      (db.working-on/clear-if-task! ds user-id task-id))
    result))

(defn set-task-today [ds user-id task-id today?]
  ;; Leaving the day lists drops the manual day position; joining one
  ;; materializes it, so that nothing has to derive it at render time.
  (let [today-val (if today? 1 0)
        before (when today? (day-membership-row ds user-id task-id))
        set-map (cond-> {:today today-val
                         :lined_up_for nil
                         :modified_at (clock/sql-now)}
                  (not today?) (-> (assoc :maybe 0)
                                   (merge (ordering/clearing :tasks-day-list))))
        result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:update :tasks
                              :set set-map
                              :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]
                              :returning [:id :today :lined_up_for :maybe :modified_at]})
                 db/jdbc-opts)]
    (when (and result before)
      (materialize-day-position! ds user-id task-id before
                                 (assoc before :today 1 :lined_up_for nil)))
    result))

(defn set-task-lined-up-for [ds user-id task-id date]
  (let [before (when date (day-membership-row ds user-id task-id))
        set-map (cond-> {:lined_up_for date
                         :today 0
                         :modified_at (clock/sql-now)}
                  (nil? date) (-> (assoc :maybe 0)
                                  (merge (ordering/clearing :tasks-day-list))))
        result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:update :tasks
                              :set set-map
                              :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]
                              :returning [:id :lined_up_for :today :maybe :modified_at]})
                 db/jdbc-opts)]
    (when (and result before)
      (materialize-day-position! ds user-id task-id before
                                 (assoc before :today 0 :lined_up_for date)))
    result))

(defn join-day!
  "Put a task on `date`'s list if nothing already does — today's marker for
  today, the lined-up date otherwise. Returns the before/after membership when
  it wrote one, nil when the day already held the task (because it is due then,
  or already flagged for it). Joining and placing are one server-side pair, so
  no caller has to sequence two writes to the same row."
  [ds user-id task-id date]
  (let [today (clock/today-str)
        before (day-membership-row ds user-id task-id)]
    (when (and before
               (not= (:due_date before) date)
               (not= date (day-order/flagged-date before today)))
      (let [after (if (= date today)
                    (set-task-today ds user-id task-id true)
                    (set-task-lined-up-for ds user-id task-id date))]
        {:before (select-keys before [:today :lined_up_for])
         :after (select-keys after [:today :lined_up_for])}))))

(defn set-task-maybe [ds user-id task-id maybe?]
  (let [maybe-val (if maybe? 1 0)]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:update :tasks
                   :set {:maybe maybe-val
                         :modified_at (clock/sql-now)}
                   :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]
                   :returning [:id :maybe :modified_at]})
      db/jdbc-opts)))

(defn promote-lined-up-tasks! [ds user-id]
  ;; The promoted tasks were already on today's list, under the lined-up marker
  ;; rather than today's, so their positions carry over untouched; only a row
  ;; that never had one is placed.
  (let [conn (db/get-conn ds)
        promoted (jdbc/execute! conn
                   (sql/format {:select [:id]
                                :from [:tasks]
                                :where [:and
                                        [:= :lined_up_for (clock/sql-today)]
                                        [:= (ordering/column :tasks-day-list) nil]
                                        (db/user-id-where-clause user-id)]})
                   db/jdbc-opts)
        result (jdbc/execute! conn
                 (sql/format {:update :tasks
                              :set {:today 1
                                    :lined_up_for nil
                                    :modified_at (clock/sql-now)}
                              :where [:and
                                      [:= :lined_up_for (clock/sql-today)]
                                      (db/user-id-where-clause user-id)]})
                 db/jdbc-opts)]
    (doseq [{:keys [id]} promoted]
      (let [row (day-membership-row ds user-id id)]
        (materialize-day-position! ds user-id id row row)))
    result))

(defn- task-urgency [ds user-id task-id]
  (:urgency (jdbc/execute-one! (db/get-conn ds)
              (sql/format {:select [:urgency]
                           :from [:tasks]
                           :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]})
              db/jdbc-opts)))

(defn place-in-urgent-list!
  "Urgent Matters is an ordering context of its own, so an item entering it is
  given a position — the top of its urgency's block, where a newly urgent thing
  is the one to look at — and an item leaving it gives that position up."
  [ds user-id task-id urgency]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :tasks
                 :set (ordering/positioning :tasks-urgent
                                            (when (contains? db/urgent-urgencies urgency)
                                              (db/top-of-order ds :tasks-urgent user-id [:= :urgency urgency])))
                 :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]})))

(defn set-task-field [ds user-id task-id field value]
  (let [normalize-fn (get db/field-normalizers field identity)
        valid-value (normalize-fn value)
        urgency-before (when (= field :urgency) (task-urgency ds user-id task-id))
        result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:update :tasks
                              :set {field valid-value
                                    :modified_at (clock/sql-now)}
                              :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]
                              :returning [:id field :modified_at]})
                 db/jdbc-opts)]
    (when (and result (= field :urgency) (not= urgency-before valid-value))
      (place-in-urgent-list! ds user-id task-id valid-value))
    result))

(defn set-task-done-at [ds user-id task-id done-date]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :tasks
                 :set {:done_at [:|| done-date " " [:coalesce [:time :done_at] "12:00:00"]]
                       :modified_at (clock/sql-now)}
                 :where [:and [:= :id task-id]
                         [:= :done 1]
                         (db/user-id-where-clause user-id)]
                 :returning [:id :done_at :modified_at]})
    db/jdbc-opts))

(defn set-task-reminder [ds user-id task-id reminder-date]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :tasks
                 :set {:reminder_date reminder-date
                       :modified_at (clock/sql-now)}
                 :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]
                 :returning [:id :reminder :reminder_date :modified_at]})
    db/jdbc-opts))

(defn acknowledge-task-reminder [ds user-id task-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :tasks
                 :set {:reminder nil
                       :reminder_date nil
                       :modified_at (clock/sql-now)}
                 :where [:and [:= :id task-id] (db/user-id-where-clause user-id)]
                 :returning [:id :reminder :reminder_date :modified_at]})
    db/jdbc-opts))

(defn activate-reminders! [ds user-id]
  (jdbc/execute! (db/get-conn ds)
    (sql/format {:update :tasks
                 :set {:reminder "active"
                       :modified_at (clock/sql-now)}
                 :where [:and
                         [:not= :reminder_date nil]
                         [:is :reminder nil]
                         [:<= :reminder_date (clock/sql-today)]
                         (db/user-id-where-clause user-id)]})
    db/jdbc-opts))

(defn convert-message-to-task [ds user-id message-id]
  (let [conn (db/get-conn ds)]
    (jdbc/with-transaction [tx conn]
      (when-let [message (jdbc/execute-one! tx
                           (sql/format {:select [:id :title :description :scope :importance :urgency]
                                        :from [:messages]
                                        :where [:and [:= :id message-id] (db/user-id-where-clause user-id)]})
                           db/jdbc-opts)]
        (let [description (or (:description message) "")
              min-order (or (:min_order (jdbc/execute-one! tx
                                          (sql/format {:select [[[:min :sort_order] :min_order]]
                                                       :from [:tasks]
                                                       :where (db/user-id-where-clause user-id)})
                                          db/jdbc-opts))
                            1.0)
              new-order (- min-order 1.0)
              task (jdbc/execute-one! tx
                     (sql/format {:insert-into :tasks
                                  :values [{:title (:title message)
                                            :sort_order new-order
                                            :user_id user-id
                                            :modified_at (clock/sql-now)
                                            :scope (or (:scope message) "both")
                                            :importance (or (:importance message) "normal")
                                            :urgency (or (:urgency message) "default")}]
                                  :returning (conj db/task-select-columns :user_id)})
                     db/jdbc-opts)]
          (when (seq description)
            (jdbc/execute-one! tx
              (sql/format {:update :tasks
                           :set {:description description :modified_at (clock/sql-now)}
                           :where [:and [:= :id (:id task)] (db/user-id-where-clause user-id)]})
              db/jdbc-opts))
          (jdbc/execute-one! tx
            (sql/format {:delete-from :messages
                         :where [:and [:= :id message-id] (db/user-id-where-clause user-id)]}))
          ;; A message can carry an urgency, so the new task can be born into
          ;; Urgent Matters and needs the position that goes with it.
          (place-in-urgent-list! tx user-id (:id task) (:urgency task))
          (tel/log! {:level :info :data {:message-id message-id :task-id (:id task) :user-id user-id}} "Message converted to task")
          (assoc task :description description :people [] :places [] :projects [] :goals []))))))
