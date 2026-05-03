(ns et.tr.reminders-integration-test
  (:require [clojure.test :refer :all]
            [et.tr.integration-helpers :refer :all]))

(use-fixtures :each with-integration-db)

(defn- add-task! [title]
  (:body (POST-json "/api/tasks" {:title title})))

(deftest set-reminder-via-api-test
  (testing "PUT /tasks/:id/reminder sets reminder_date"
    (let [task (add-task! "Call dentist")
          resp (PUT-json (str "/api/tasks/" (:id task) "/reminder") {:reminder-date "2026-05-15"})]
      (is (= 200 (:status resp)))
      (is (= "2026-05-15" (:reminder_date (:body resp))))
      (is (nil? (:reminder (:body resp))))))

  (testing "task shows reminder_date in GET"
    (let [task (add-task! "Check reminder")
          _ (PUT-json (str "/api/tasks/" (:id task) "/reminder") {:reminder-date "2026-06-01"})
          resp (GET-json (str "/api/tasks/" (:id task)))]
      (is (= 200 (:status resp)))
      (is (= "2026-06-01" (:reminder_date (:body resp)))))))

(deftest acknowledge-reminder-via-api-test
  (testing "PUT /tasks/:id/acknowledge-reminder clears reminder"
    (let [task (add-task! "Review report")
          _ (PUT-json (str "/api/tasks/" (:id task) "/reminder") {:reminder-date "2026-04-01"})
          resp (PUT-json (str "/api/tasks/" (:id task) "/acknowledge-reminder") {})]
      (is (= 200 (:status resp)))
      (is (nil? (:reminder (:body resp))))
      (is (nil? (:reminder_date (:body resp)))))))

(deftest activate-reminders-via-test-endpoint-test
  (testing "POST /test/activate-reminders activates past reminders"
    (let [task (add-task! "Past reminder")
          _ (PUT-json (str "/api/tasks/" (:id task) "/reminder") {:reminder-date "2020-01-01"})
          activate-resp (POST-json "/api/test/activate-reminders" {})
          task-resp (GET-json (str "/api/tasks/" (:id task)))]
      (is (= 200 (:status activate-resp)))
      (is (= "active" (:reminder (:body task-resp))))))

  (testing "activate does not affect future reminders"
    (let [task (add-task! "Future reminder")
          _ (PUT-json (str "/api/tasks/" (:id task) "/reminder") {:reminder-date "2099-12-31"})
          _ (POST-json "/api/test/activate-reminders" {})
          task-resp (GET-json (str "/api/tasks/" (:id task)))]
      (is (nil? (:reminder (:body task-resp)))))))

(deftest today-mode-includes-active-reminders-test
  (testing "active reminder task appears in today sort mode"
    (let [task (add-task! "Reminder in today")
          _ (PUT-json (str "/api/tasks/" (:id task) "/reminder") {:reminder-date "2020-01-01"})
          _ (POST-json "/api/test/activate-reminders" {})
          resp (GET-json "/api/tasks?sort=today")]
      (is (some #(= "Reminder in today" (:title %)) (:body resp))))))

(deftest full-reminder-lifecycle-test
  (testing "set reminder, activate, acknowledge"
    (let [task (add-task! "Lifecycle task")
          _ (PUT-json (str "/api/tasks/" (:id task) "/reminder") {:reminder-date "2020-01-01"})
          _ (POST-json "/api/test/activate-reminders" {})
          active (GET-json (str "/api/tasks/" (:id task)))
          _ (is (= "active" (:reminder (:body active))))
          _ (PUT-json (str "/api/tasks/" (:id task) "/acknowledge-reminder") {})
          cleared (GET-json (str "/api/tasks/" (:id task)))]
      (is (nil? (:reminder (:body cleared))))
      (is (nil? (:reminder_date (:body cleared)))))))
