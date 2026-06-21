(ns et.tr.worker
  (:require [et.tr.db :as db]
            [et.tr.db.meeting-series :as db.meeting-series]
            [et.tr.db.recurring-task :as db.recurring-task]
            [et.tr.db.journal :as db.journal]
            [et.tr.db.journal-entry :as db.journal-entry]
            [et.tr.db.event :as db.event]
            [et.tr.db.task :as db.task]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel])
  (:import [java.util.concurrent Executors TimeUnit]
           [java.time LocalDateTime]))

(defn- all-user-ids [ds]
  (mapv :id (jdbc/execute! (db/get-conn ds)
              (sql/format {:select [:id] :from [:users]})
              db/jdbc-opts)))

(defn run-meeting-series-check [ds]
  (tel/log! :info "Meeting series worker: check started")
  (try
    (doseq [user-id (all-user-ids ds)]
      (let [created (db.meeting-series/auto-create-meetings ds user-id {:short-circuit? true})]
        (doseq [meet created]
          (tel/log! {:level :info :data {:meet-id (:id meet) :title (:title meet) :date (:start_date meet) :user-id user-id}}
                    "Meeting series worker: created meeting"))))
    (catch Exception e
      (tel/log! {:level :error :data {:error (.getMessage e)}} "Meeting series worker failed"))))

(defn run-recurring-tasks-check [ds]
  (tel/log! :info "Recurring tasks worker: check started")
  (try
    (doseq [user-id (all-user-ids ds)]
      (let [created (db.recurring-task/auto-create-tasks ds user-id {:short-circuit? true})]
        (doseq [task created]
          (tel/log! {:level :info :data {:task-id (:id task) :title (:title task) :date (:due_date task) :user-id user-id}}
                    "Recurring tasks worker: created task"))))
    (catch Exception e
      (tel/log! {:level :error :data {:error (.getMessage e)}} "Recurring tasks worker failed"))))

(defn run-journals-check [ds]
  (tel/log! :info "Journals worker: check started")
  (try
    (doseq [user-id (all-user-ids ds)]
      (let [created (db.journal/auto-create-journal-entries ds user-id)]
        (doseq [entry created]
          (tel/log! {:level :info :data {:entry-id (:id entry) :title (:title entry) :date (:entry_date entry) :user-id user-id}}
                    "Journals worker: created entry"))))
    (catch Exception e
      (tel/log! {:level :error :data {:error (.getMessage e)}} "Journals worker failed"))))

(defn run-journal-prune-check [ds]
  (tel/log! :info "Journal prune worker: check started")
  (try
    (doseq [user-id (all-user-ids ds)]
      (let [{:keys [deleted-count]} (db.journal-entry/prune-empty-entries ds user-id)]
        (when (pos? deleted-count)
          (tel/log! {:level :info :data {:user-id user-id :count deleted-count}}
                    "Journal prune worker: pruned empty entries"))))
    (catch Exception e
      (tel/log! {:level :error :data {:error (.getMessage e)}} "Journal prune worker failed"))))

(defn run-events-prune-check [ds]
  (tel/log! :info "Events prune worker: check started")
  (try
    (doseq [user-id (all-user-ids ds)]
      (let [deleted (db.event/prune-events! ds user-id)]
        (when (pos? deleted)
          (tel/log! {:level :info :data {:user-id user-id :count deleted}}
                    "Events prune worker: pruned events"))))
    (let [deleted (db.event/prune-system-events! ds)]
      (when (pos? deleted)
        (tel/log! {:level :info :data {:count deleted}}
                  "Events prune worker: pruned system events")))
    (catch Exception e
      (tel/log! {:level :error :data {:error (.getMessage e)}} "Events prune worker failed"))))

(defn run-lined-up-promotion [ds]
  (tel/log! :info "Lined-up promotion worker: check started")
  (try
    (doseq [user-id (all-user-ids ds)]
      (db.task/promote-lined-up-tasks! ds user-id))
    (catch Exception e
      (tel/log! {:level :error :data {:error (.getMessage e)}} "Lined-up promotion worker failed"))))

(defn run-reminders-check [ds]
  (tel/log! :info "Reminders worker: check started")
  (try
    (doseq [user-id (all-user-ids ds)]
      (db.task/activate-reminders! ds user-id))
    (catch Exception e
      (tel/log! {:level :error :data {:error (.getMessage e)}} "Reminders worker failed"))))

(defn- seconds-until-minute [target-minute]
  (let [now (LocalDateTime/now)
        current-minute (.getMinute now)
        current-second (.getSecond now)
        diff (- target-minute current-minute)
        minutes-to-wait (if (pos? diff) diff (+ diff 60))]
    (- (* minutes-to-wait 60) current-second)))

(defn start-scheduler [ds]
  (let [scheduler (Executors/newSingleThreadScheduledExecutor)
        initial-delay (seconds-until-minute 40)]
    (tel/log! {:level :info :data {:initial-delay-seconds initial-delay}}
              "Worker: scheduling (every hour at :40)")
    (.scheduleAtFixedRate scheduler
      ^Runnable (fn []
                  (run-meeting-series-check ds)
                  (run-recurring-tasks-check ds)
                  (run-journals-check ds)
                  (run-journal-prune-check ds)
                  (run-events-prune-check ds)
                  (run-lined-up-promotion ds)
                  (run-reminders-check ds))
      initial-delay
      3600
      TimeUnit/SECONDS)
    scheduler))
