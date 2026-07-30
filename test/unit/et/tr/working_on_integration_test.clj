(ns et.tr.working-on-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [et.tr.integration-helpers :refer [*ds* *user-id* DELETE-json GET-json
                                               POST-json PUT-json with-integration-db]]
            [et.tr.clock :as clock]
            [et.tr.db :as db]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]))

(use-fixtures :each with-integration-db)

(defn- add-task! [title]
  (:id (:body (POST-json "/api/tasks" {:title title}))))

(defn- stamp-set-on! [date]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:update :working_on
                 :set {:set_on date}
                 :where [:= :user_id *user-id*]})))

;; Not PUT /done: that clears the marker, and the point is a marker that
;; outlived its task being done — the shape a row written before the doneness
;; clause existed still has.
(defn- stamp-task-done! [task-id]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:update :tasks
                 :set {:done 1}
                 :where [:= :id task-id]})))

(deftest working-on-starts-empty
  (testing "GET /api/working-on reports both keys with nothing set"
    (let [{:keys [status body]} (GET-json "/api/working-on")]
      (is (= 200 status))
      (is (= {:task-id nil :set-on nil} body)))))

(deftest set-work-on-round-trips
  (testing "PUT /api/tasks/:id/work-on sets and clears the marker"
    (let [id (add-task! "Write the thing")]
      (let [{:keys [status body]} (PUT-json (str "/api/tasks/" id "/work-on") {:work-on true})]
        (is (= 200 status))
        (is (= {:task-id id :set-on (clock/today-str)} body)))
      (is (= {:task-id id :set-on (clock/today-str)} (:body (GET-json "/api/working-on"))))
      (let [{:keys [status body]} (PUT-json (str "/api/tasks/" id "/work-on") {:work-on false})]
        (is (= 200 status))
        (is (= {:task-id nil :set-on nil} body)))
      (is (= {:task-id nil :set-on nil} (:body (GET-json "/api/working-on")))))))

(deftest set-work-on-moves-the-marker
  (testing "setting it on a second task takes it off the first"
    (let [first-id (add-task! "First")
          second-id (add-task! "Second")]
      (PUT-json (str "/api/tasks/" first-id "/work-on") {:work-on true})
      (let [{:keys [status body]} (PUT-json (str "/api/tasks/" second-id "/work-on") {:work-on true})]
        (is (= 200 status))
        (is (= second-id (:task-id body))))
      (is (= second-id (:task-id (:body (GET-json "/api/working-on"))))))))

(deftest unset-work-on-on-another-task-is-a-no-op
  (testing "clearing a task that does not hold the marker leaves it in place"
    (let [first-id (add-task! "First")
          second-id (add-task! "Second")]
      (PUT-json (str "/api/tasks/" second-id "/work-on") {:work-on true})
      (let [{:keys [status body]} (PUT-json (str "/api/tasks/" first-id "/work-on") {:work-on false})]
        (is (= 200 status))
        (is (= second-id (:task-id body)))))))

(deftest set-work-on-requires-field
  (testing "missing :work-on yields 400"
    (let [id (add-task! "Write the thing")
          {:keys [status]} (PUT-json (str "/api/tasks/" id "/work-on") {})]
      (is (= 400 status)))))

(deftest set-work-on-unknown-task-404
  (testing "unknown task yields 404 both ways"
    (is (= 404 (:status (PUT-json "/api/tasks/99999/work-on" {:work-on true}))))
    (is (= 404 (:status (PUT-json "/api/tasks/99999/work-on" {:work-on false}))))))

(deftest marking-the-task-done-clears-the-marker
  (testing "PUT /api/tasks/:id/done drops the marker with it"
    (let [id (add-task! "Finish up")]
      (PUT-json (str "/api/tasks/" id "/work-on") {:work-on true})
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/done") {:done true}))))
      (is (= {:task-id nil :set-on nil} (:body (GET-json "/api/working-on")))))))

(deftest set-work-on-done-task-404
  (testing "a done task is refused exactly like a task that is not the user's"
    (let [id (add-task! "Already finished")]
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/done") {:done true}))))
      (let [{:keys [status body]} (PUT-json (str "/api/tasks/" id "/work-on") {:work-on true})]
        (is (= 404 status))
        (is (= {:error "Task not found"} body)))
      (is (= {:task-id nil :set-on nil} (:body (GET-json "/api/working-on")))))))

(deftest clear-work-on-works-on-a-done-task
  (testing "a marker that outlived its task being done is still removable"
    (let [id (add-task! "Finish up")]
      (PUT-json (str "/api/tasks/" id "/work-on") {:work-on true})
      (stamp-task-done! id)
      (is (= {:task-id id :set-on (clock/today-str)} (:body (GET-json "/api/working-on"))))
      (let [{:keys [status body]} (PUT-json (str "/api/tasks/" id "/work-on") {:work-on false})]
        (is (= 200 status))
        (is (= {:task-id nil :set-on nil} body)))
      (is (= {:task-id nil :set-on nil} (:body (GET-json "/api/working-on")))))))

(deftest deleting-the-task-clears-the-marker
  (testing "DELETE /api/tasks/:id drops the marker with it"
    (let [id (add-task! "Never mind")]
      (PUT-json (str "/api/tasks/" id "/work-on") {:work-on true})
      (is (= 200 (:status (DELETE-json (str "/api/tasks/" id)))))
      (is (= {:task-id nil :set-on nil} (:body (GET-json "/api/working-on")))))))

(deftest a-marker-from-an-earlier-day-is-invisible
  (testing "the day stamp expires the marker without anything unsetting it"
    (let [id (add-task! "Yesterday's business")]
      (PUT-json (str "/api/tasks/" id "/work-on") {:work-on true})
      (stamp-set-on! "2020-01-01")
      (is (= {:task-id nil :set-on nil} (:body (GET-json "/api/working-on")))))))
