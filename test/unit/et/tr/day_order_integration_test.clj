(ns et.tr.day-order-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [et.tr.integration-helpers :refer [*ds* GET-json POST-json PUT-json
                                               with-integration-db]]
            [et.tr.db.task :as db.task]
            [et.tr.db.user :as db.user]))

(use-fixtures :each with-integration-db)

(defn- add-task! [title]
  (:body (POST-json "/api/tasks" {:title title})))

(defn- task [id]
  (:body (GET-json (str "/api/tasks/" id))))

(deftest day-order-starts-unset
  (testing "a fresh task derives its day position, so it stores none"
    (let [id (:id (add-task! "Nothing scheduled"))]
      (is (nil? (:day_order (task id)))))))

(deftest set-day-order-round-trips
  (testing "PUT /api/tasks/:id/day-order stores the value and reads back"
    (let [id (:id (add-task! "Do this first"))
          {:keys [status body]} (PUT-json (str "/api/tasks/" id "/day-order") {:day-order 509.5})]
      (is (= 200 status))
      (is (= {:success true :day_order 509.5} body))
      (is (= 509.5 (:day_order (task id)))))))

(deftest clearing-day-order-hands-the-task-back-to-its-time
  (testing "a nil value removes the stored position"
    (let [id (:id (add-task! "Do this first"))]
      (PUT-json (str "/api/tasks/" id "/day-order") {:day-order 509.5})
      (let [{:keys [status body]} (PUT-json (str "/api/tasks/" id "/day-order") {:day-order nil})]
        (is (= 200 status))
        (is (= {:success true :day_order nil} body)))
      (is (nil? (:day_order (task id)))))))

(deftest day-order-and-sort-order-are-independent
  (testing "moving a task in the day list leaves the Tasks page order alone"
    (let [{:keys [id sort_order]} (add-task! "Same task, two orders")]
      (PUT-json (str "/api/tasks/" id "/day-order") {:day-order 1441.25})
      (is (= sort_order (:sort_order (task id))))))

  (testing "moving a task on the Tasks page leaves the day list order alone"
    (let [first-id (:id (add-task! "First"))
          second-id (:id (add-task! "Second"))]
      (PUT-json (str "/api/tasks/" second-id "/day-order") {:day-order 1441.25})
      (is (= 200 (:status (POST-json (str "/api/tasks/" second-id "/reorder")
                                     {:target-task-id first-id :position "after"}))))
      (is (= 1441.25 (:day_order (task second-id)))))))

(deftest a-due-date-drops-the-day-position
  (testing "the stored position is relative to one day, so changing days clears it"
    (let [id (:id (add-task! "Moves to another day"))]
      (PUT-json (str "/api/tasks/" id "/day-order") {:day-order 509.5})
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/due-date") {:due-date "2026-07-16"}))))
      (is (nil? (:day_order (task id))))))

  (testing "clearing the due date clears it too"
    (let [id (:id (add-task! "Loses its date"))]
      (PUT-json (str "/api/tasks/" id "/due-date") {:due-date "2026-07-16"})
      (PUT-json (str "/api/tasks/" id "/day-order") {:day-order 509.5})
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/due-date") {:due-date nil}))))
      (is (nil? (:day_order (task id)))))))

(deftest flagging-a-task-for-a-day-keeps-its-position
  (testing "the drop that brings a task in also sets the position, so neither write may undo the other"
    (let [id (:id (add-task! "Dragged in"))]
      (PUT-json (str "/api/tasks/" id "/day-order") {:day-order 1441.5})
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/today") {:today true}))))
      (is (= 1441.5 (:day_order (task id))))
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/lined-up-for") {:lined_up_for "2026-07-16"}))))
      (is (= 1441.5 (:day_order (task id)))))))

(deftest leaving-the-day-lists-drops-the-position
  (testing "unlinking from today clears it, so the task comes back at the end of the list"
    (let [id (:id (add-task! "Unlinked"))]
      (PUT-json (str "/api/tasks/" id "/today") {:today true})
      (PUT-json (str "/api/tasks/" id "/day-order") {:day-order 705.0})
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/today") {:today false}))))
      (is (nil? (:day_order (task id))))))

  (testing "clearing the queued day clears it too"
    (let [id (:id (add-task! "Unqueued"))]
      (PUT-json (str "/api/tasks/" id "/lined-up-for") {:lined_up_for "2026-07-16"})
      (PUT-json (str "/api/tasks/" id "/day-order") {:day-order 705.0})
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/lined-up-for") {:lined_up_for nil}))))
      (is (nil? (:day_order (task id))))))

  (testing "being done takes the task off every day list, position included"
    (let [id (:id (add-task! "Finished"))]
      (PUT-json (str "/api/tasks/" id "/today") {:today true})
      (PUT-json (str "/api/tasks/" id "/day-order") {:day-order 705.0})
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/done") {:done true}))))
      (is (nil? (:day_order (task id)))))))

(deftest non-numeric-day-order-is-refused
  (testing "a non-number yields 400 and stores nothing"
    (let [id (:id (add-task! "Untouched"))]
      (PUT-json (str "/api/tasks/" id "/day-order") {:day-order 509.5})
      (doseq [value ["509.5" true {} []]]
        (let [{:keys [status body]} (PUT-json (str "/api/tasks/" id "/day-order") {:day-order value})]
          (is (= 400 status) (str "value " (pr-str value)))
          (is (= {:error "day-order must be a number or null"} body))))
      (is (= 509.5 (:day_order (task id)))))))

(deftest unknown-and-foreign-tasks-404
  (testing "an id that is not the current user's task is refused"
    (let [other (db.user/create-user *ds* "other-user" "testpass")
          theirs (:id (db.task/add-task *ds* (:id other) "Not yours"))]
      (is (= 404 (:status (PUT-json "/api/tasks/99999/day-order" {:day-order 1.0}))))
      (let [{:keys [status body]} (PUT-json (str "/api/tasks/" theirs "/day-order") {:day-order 1.0})]
        (is (= 404 status))
        (is (= {:error "Task not found"} body)))
      (is (nil? (:day_order (db.task/get-task *ds* (:id other) theirs)))))))
