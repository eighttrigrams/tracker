(ns et.tr.worker-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.worker :as worker]
            [et.tr.db :as db]
            [et.tr.db.recurring-task :as db.recurring-task]
            [et.tr.db.task :as db.task]
            [et.tr.db.meeting-series :as db.meeting-series]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql])
  (:import [java.time LocalDate]))

(use-fixtures :each with-in-memory-db)

(defn- today-dow []
  (str (.getValue (.getDayOfWeek (LocalDate/now)))))

(defn- next-dow [dow-str]
  (let [d (Integer/parseInt dow-str)]
    (str (if (= d 7) 1 (inc d)))))

(defn- schedule-days-today-and-next []
  (let [dow (today-dow)]
    (str dow "," (next-dow dow))))

;; ── Recurring Tasks ──

(defn- setup-today-type-recurring-task []
  (let [rt (db.recurring-task/add-recurring-task *ds* *user-id* "Test Task")]
    (db.recurring-task/set-recurring-task-schedule
      *ds* *user-id* (:id rt)
      (schedule-days-today-and-next) nil "weekly" false "today")
    rt))

(deftest recurring-task-today-type-creates-task
  (testing "creates a task when none exists"
    (let [rt (setup-today-type-recurring-task)]
      (worker/run-recurring-tasks-check *ds*)
      (is (= 1 (count (db.task/list-tasks *ds* *user-id* {:recurring-task-id (:id rt)})))))))

(deftest recurring-task-today-type-no-duplicate
  (testing "does not create another task when an active one exists"
    (let [rt (setup-today-type-recurring-task)]
      (worker/run-recurring-tasks-check *ds*)
      (worker/run-recurring-tasks-check *ds*)
      (is (= 1 (count (db.task/list-tasks *ds* *user-id* {:recurring-task-id (:id rt)})))))))

(deftest recurring-task-today-type-done-then-creates-next
  (testing "after marking done, creates the next scheduled date, not today again"
    (let [rt (setup-today-type-recurring-task)]
      (worker/run-recurring-tasks-check *ds*)
      (let [task-id (:id (first (db.task/list-tasks *ds* *user-id* {:recurring-task-id (:id rt)})))]
        (db.task/set-task-done *ds* *user-id* task-id true)
        (worker/run-recurring-tasks-check *ds*)
        (let [updated-tasks (db.task/list-tasks *ds* *user-id* {:recurring-task-id (:id rt)})
              new-task (first (filter #(= 0 (:done %)) updated-tasks))]
          (is (some? new-task))
          (is (some? (:lined_up_for new-task)) "next task should be lined up for a future date, not today"))))))

(deftest recurring-task-today-type-done-then-stable
  (testing "after done+create cycle, third run creates nothing new"
    (let [rt (setup-today-type-recurring-task)]
      (worker/run-recurring-tasks-check *ds*)
      (let [task-id (:id (first (db.task/list-tasks *ds* *user-id* {:recurring-task-id (:id rt)})))]
        (db.task/set-task-done *ds* *user-id* task-id true)
        (worker/run-recurring-tasks-check *ds*)
        (let [count-before (count (db.task/list-tasks *ds* *user-id* {:recurring-task-id (:id rt)}))]
          (worker/run-recurring-tasks-check *ds*)
          (is (= count-before (count (db.task/list-tasks *ds* *user-id* {:recurring-task-id (:id rt)})))))))))

(deftest recurring-task-today-type-delete-creates-next
  (testing "deleting today's task immediately creates the next scheduled item"
    (let [rt (setup-today-type-recurring-task)]
      (worker/run-recurring-tasks-check *ds*)
      (let [task-id (:id (first (db.task/list-tasks *ds* *user-id* {:recurring-task-id (:id rt)})))]
        (db.task/delete-task *ds* *user-id* task-id)
        (let [tasks (db.task/list-tasks *ds* *user-id* {:recurring-task-id (:id rt)})
              new-task (first tasks)]
          (is (= 1 (count tasks)))
          (is (some? new-task))
          (is (some? (:lined_up_for new-task)) "next task should be lined up for a future date, not today")
          (is (zero? (:today new-task)) "next task should not be marked for today"))))))

(deftest recurring-task-today-type-delete-no-create-outside-window
  (testing "deleting today's task does not create when next scheduled date is beyond today+4"
    (let [rt (db.recurring-task/add-recurring-task *ds* *user-id* "Weekly Only Today")]
      (db.recurring-task/set-recurring-task-schedule
        *ds* *user-id* (:id rt)
        (today-dow) nil "weekly" false "today")
      (worker/run-recurring-tasks-check *ds*)
      (let [task-id (:id (first (db.task/list-tasks *ds* *user-id* {:recurring-task-id (:id rt)})))]
        (db.task/delete-task *ds* *user-id* task-id)
        (is (zero? (count (db.task/list-tasks *ds* *user-id* {:recurring-task-id (:id rt)}))))))))

(deftest recurring-task-today-type-delete-no-create-when-active-exists
  (testing "deleting today's task does not create when another active task exists in the series"
    (let [rt (setup-today-type-recurring-task)]
      (worker/run-recurring-tasks-check *ds*)
      (let [task-id (:id (first (db.task/list-tasks *ds* *user-id* {:recurring-task-id (:id rt)})))
            future-date (str (.plusDays (LocalDate/now) 2))]
        (db.recurring-task/create-task-for-recurring *ds* *user-id* (:id rt) future-date nil)
        (db.task/delete-task *ds* *user-id* task-id)
        (let [tasks (db.task/list-tasks *ds* *user-id* {:recurring-task-id (:id rt)})]
          (is (= 1 (count tasks)) "only the pre-existing future task should remain")
          (is (= future-date (:lined_up_for (first tasks)))))))))

;; ── Meeting Series ──

(defn- setup-meeting-series []
  (let [ms (db.meeting-series/add-meeting-series *ds* *user-id* "Test Series")]
    (db.meeting-series/set-meeting-series-schedule
      *ds* *user-id* (:id ms)
      (schedule-days-today-and-next) "09:00" "weekly" false)
    ms))

(deftest meeting-series-creates-meets
  (testing "creates meets for scheduled dates"
    (let [ms (setup-meeting-series)]
      (worker/run-meeting-series-check *ds*)
      (let [dates (db.meeting-series/get-taken-dates *ds* *user-id* (:id ms))]
        (is (pos? (count dates)))))))

(deftest meeting-series-idempotent
  (testing "running twice does not duplicate meets"
    (let [ms (setup-meeting-series)]
      (worker/run-meeting-series-check *ds*)
      (let [count-after-first (count (db.meeting-series/get-taken-dates *ds* *user-id* (:id ms)))]
        (worker/run-meeting-series-check *ds*)
        (is (= count-after-first (count (db.meeting-series/get-taken-dates *ds* *user-id* (:id ms)))))))))

;; ── Reminders ──

(defn- create-task-with-reminder [reminder-date]
  (let [task (db.task/add-task *ds* *user-id* "Reminder Task")]
    (db.task/set-task-reminder *ds* *user-id* (:id task) reminder-date)
    task))

(deftest reminders-activates-due-reminders
  (testing "activates reminders whose date has arrived"
    (let [task (create-task-with-reminder (str (LocalDate/now)))]
      (worker/run-reminders-check *ds*)
      (let [updated (db.task/get-task *ds* *user-id* (:id task))]
        (is (= "active" (:reminder updated)))))))

(deftest reminders-does-not-activate-future-reminders
  (testing "does not activate reminders with a future date"
    (let [task (create-task-with-reminder (str (.plusDays (LocalDate/now) 2)))]
      (worker/run-reminders-check *ds*)
      (let [updated (db.task/get-task *ds* *user-id* (:id task))]
        (is (nil? (:reminder updated)))))))
