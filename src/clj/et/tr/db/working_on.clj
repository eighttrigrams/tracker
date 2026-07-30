(ns et.tr.db.working-on
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.tr.clock :as clock]
            [et.tr.db :as db]))

(defn- owned-open-task? [conn user-id task-id]
  (some? (jdbc/execute-one! conn
           (sql/format {:select [:id]
                        :from [:tasks]
                        :where [:and [:= :id task-id] [:= :done 0]
                                (db/user-id-where-clause user-id)]})
           db/jdbc-opts)))

(defn get-working-on
  "The one task the user is working on, as {:task-id N :set-on \"yyyy-MM-dd\"},
  or nil. A row stamped with an earlier day is not reported — that date
  comparison is the whole of the overnight expiry, so nothing has to run at
  midnight. Stale rows are left in place (reads stay side-effect-free); the next
  set overwrites."
  [ds user-id]
  (let [row (jdbc/execute-one! (db/get-conn ds)
              (sql/format {:select [:task_id :set_on]
                           :from [:working_on]
                           :where (db/user-id-where-clause user-id)})
              db/jdbc-opts)]
    (when (= (:set_on row) (clock/today-str))
      {:task-id (:task_id row) :set-on (:set_on row)})))

(defn set-working-on!
  "Point the user's marker at task-id, stamped with today, replacing whatever it
  pointed at before. Returns the resulting singleton, or nil when the task is
  not this user's or is already done: the done hook clears the marker on the
  transition, and refusing done tasks here makes that an invariant on the state
  rather than only on the transition."
  [ds user-id task-id]
  (let [conn (db/get-conn ds)]
    (when (owned-open-task? conn user-id task-id)
      (let [existing (jdbc/execute-one! conn
                       (sql/format {:select [:user_id]
                                    :from [:working_on]
                                    :where (db/user-id-where-clause user-id)})
                       db/jdbc-opts)]
        (if existing
          (jdbc/execute-one! conn
            (sql/format {:update :working_on
                         :set {:task_id task-id :set_on (clock/today-str)}
                         :where (db/user-id-where-clause user-id)}))
          (jdbc/execute-one! conn
            (sql/format {:insert-into :working_on
                         :values [{:user_id user-id
                                   :task_id task-id
                                   :set_on (clock/today-str)}]}))))
      (get-working-on ds user-id))))

(defn clear-if-task!
  "Drop the user's marker if it points at task-id. Takes a datasource or an
  open transaction, so the task-done and task-deleted hooks can clear inside
  their own transaction."
  [ds user-id task-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:delete-from :working_on
                 :where [:and (db/user-id-where-clause user-id) [:= :task_id task-id]]})))

(defn clear-for-user!
  "Drop the user's marker whatever it points at."
  [ds user-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:delete-from :working_on
                 :where (db/user-id-where-clause user-id)})))
