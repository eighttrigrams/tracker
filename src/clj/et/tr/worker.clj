(ns et.tr.worker
  (:require [et.tr.db :as db]
            [et.tr.db.meeting-series :as db.meeting-series]
            [et.tr.db.recurring-task :as db.recurring-task]
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
                  (run-recurring-tasks-check ds))
      initial-delay
      3600
      TimeUnit/SECONDS)
    scheduler))
