(ns et.tr.db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db :as db]))

(def ^:dynamic *ds* nil)

(defn with-in-memory-db [f]
  (let [conn (db/init-conn {:type :sqlite-memory})]
    (try
      (binding [*ds* conn]
        (f))
      (finally
        (when-let [pc (:persistent-conn conn)]
          (.close pc))))))

(use-fixtures :each with-in-memory-db)

(deftest add-task-test
  (testing "adds task with title and returns it"
    (let [task (db/add-task *ds* nil "Test task")]
      (is (some? (:id task)))
      (is (= "Test task" (:title task)))
      (is (= "" (:description task)))
      (is (some? (:created_at task)))
      (is (some? (:sort_order task))))))

(deftest add-task-sort-order-test
  (testing "new tasks get decreasing sort_order (appear at top)"
    (let [task1 (db/add-task *ds* nil "First")
          task2 (db/add-task *ds* nil "Second")
          task3 (db/add-task *ds* nil "Third")]
      (is (< (:sort_order task3) (:sort_order task2)))
      (is (< (:sort_order task2) (:sort_order task1))))))

(deftest list-tasks-empty-test
  (testing "returns empty list when no tasks"
    (is (= [] (db/list-tasks *ds* nil)))))

(deftest list-tasks-with-categories-test
  (testing "returns tasks with categories"
    (let [task (db/add-task *ds* nil "Task with categories")
          person (db/add-person *ds* nil "Alice")]
      (db/categorize-task *ds* nil (:id task) "person" (:id person))
      (let [tasks (db/list-tasks *ds* nil)
            retrieved (first tasks)]
        (is (= 1 (count tasks)))
        (is (= "Task with categories" (:title retrieved)))
        (is (= [{:id (:id person) :name "Alice"}] (:people retrieved)))
        (is (= [] (:places retrieved)))
        (is (= [] (:projects retrieved)))
        (is (= [] (:goals retrieved)))))))

(deftest list-tasks-recent-mode-test
  (testing "recent mode returns all tasks"
    (db/add-task *ds* nil "First")
    (db/add-task *ds* nil "Second")
    (db/add-task *ds* nil "Third")
    (let [tasks (db/list-tasks *ds* nil :recent)]
      (is (= 3 (count tasks)))
      (is (= #{"First" "Second" "Third"} (set (map :title tasks)))))))

(deftest list-tasks-manual-mode-test
  (testing "manual mode orders by sort_order ASC"
    (db/add-task *ds* nil "First")
    (Thread/sleep 10)
    (db/add-task *ds* nil "Second")
    (Thread/sleep 10)
    (db/add-task *ds* nil "Third")
    (let [tasks (db/list-tasks *ds* nil :manual)]
      (is (= ["Third" "Second" "First"] (map :title tasks))))))

(deftest reorder-task-updates-sort-order-test
  (testing "updates task sort_order"
    (let [task (db/add-task *ds* nil "Test")
          result (db/reorder-task *ds* nil (:id task) 99.5)]
      (is (= true (:success result)))
      (is (= 99.5 (:sort_order result)))
      (is (= 99.5 (db/get-task-sort-order *ds* nil (:id task)))))))

(deftest reorder-task-changes-position-test
  (testing "reordering changes position in manual mode"
    (let [task1 (db/add-task *ds* nil "A")
          _task2 (db/add-task *ds* nil "B")
          task3 (db/add-task *ds* nil "C")
          initial-order (map :title (db/list-tasks *ds* nil :manual))]
      (is (= ["C" "B" "A"] initial-order))
      (db/reorder-task *ds* nil (:id task1) (- (:sort_order task3) 0.5))
      (let [new-order (map :title (db/list-tasks *ds* nil :manual))]
        (is (= ["A" "C" "B"] new-order))))))

(deftest get-task-sort-order-test
  (testing "returns sort_order for task"
    (let [task (db/add-task *ds* nil "Test")]
      (is (= (:sort_order task) (db/get-task-sort-order *ds* nil (:id task))))))

  (testing "returns nil for non-existent task"
    (is (nil? (db/get-task-sort-order *ds* nil 99999)))))

(deftest update-task-test
  (testing "updates title and description"
    (let [task (db/add-task *ds* nil "Original")
          updated (db/update-task *ds* nil (:id task) "Updated" "New description")]
      (is (= "Updated" (:title updated)))
      (is (= "New description" (:description updated)))
      (is (= (:id task) (:id updated))))))

(deftest categorize-task-test
  (testing "can categorize task with person, place, project, goal"
    (let [task (db/add-task *ds* nil "Task")
          person (db/add-person *ds* nil "Bob")
          place (db/add-place *ds* nil "Office")
          project (db/add-project *ds* nil "Website")
          goal (db/add-goal *ds* nil "Launch")]
      (db/categorize-task *ds* nil (:id task) "person" (:id person))
      (db/categorize-task *ds* nil (:id task) "place" (:id place))
      (db/categorize-task *ds* nil (:id task) "project" (:id project))
      (db/categorize-task *ds* nil (:id task) "goal" (:id goal))
      (let [tasks (db/list-tasks *ds* nil)
            categorized (first tasks)]
        (is (= 1 (count (:people categorized))))
        (is (= 1 (count (:places categorized))))
        (is (= 1 (count (:projects categorized))))
        (is (= 1 (count (:goals categorized))))))))

(deftest uncategorize-task-test
  (testing "can uncategorize task"
    (let [task (db/add-task *ds* nil "Task")
          person (db/add-person *ds* nil "Carol")]
      (db/categorize-task *ds* nil (:id task) "person" (:id person))
      (is (= 1 (count (:people (first (db/list-tasks *ds* nil))))))
      (db/uncategorize-task *ds* nil (:id task) "person" (:id person))
      (is (= 0 (count (:people (first (db/list-tasks *ds* nil)))))))))

(deftest people-crud-test
  (testing "add and list people"
    (db/add-person *ds* nil "Alice")
    (db/add-person *ds* nil "Bob")
    (let [people (db/list-people *ds* nil)]
      (is (= 2 (count people)))
      (is (= ["Alice" "Bob"] (map :name people))))))

(deftest places-crud-test
  (testing "add and list places"
    (db/add-place *ds* nil "Home")
    (db/add-place *ds* nil "Work")
    (let [places (db/list-places *ds* nil)]
      (is (= 2 (count places)))
      (is (= ["Home" "Work"] (map :name places))))))

(deftest projects-crud-test
  (testing "add and list projects"
    (db/add-project *ds* nil "Alpha")
    (db/add-project *ds* nil "Beta")
    (let [projects (db/list-projects *ds* nil)]
      (is (= 2 (count projects)))
      (is (= ["Alpha" "Beta"] (map :name projects))))))

(deftest goals-crud-test
  (testing "add and list goals"
    (db/add-goal *ds* nil "Learn Clojure")
    (db/add-goal *ds* nil "Ship product")
    (let [goals (db/list-goals *ds* nil)]
      (is (= 2 (count goals)))
      (is (= ["Learn Clojure" "Ship product"] (map :name goals))))))

(deftest sort-order-midpoint-test
  (testing "midpoint insertion maintains order"
    (let [task1 (db/add-task *ds* nil "A")
          task2 (db/add-task *ds* nil "B")
          task3 (db/add-task *ds* nil "C")
          order1 (:sort_order task1)
          order2 (:sort_order task2)
          midpoint (/ (+ order1 order2) 2.0)]
      (db/reorder-task *ds* nil (:id task3) midpoint)
      (let [tasks (db/list-tasks *ds* nil :manual)]
        (is (= ["B" "C" "A"] (map :title tasks)))))))

(deftest set-task-due-date-test
  (testing "sets due date on task"
    (let [task (db/add-task *ds* nil "Task with due date")
          result (db/set-task-due-date *ds* nil (:id task) "2026-01-15")]
      (is (= (:id task) (:id result)))
      (is (= "2026-01-15" (:due_date result)))))

  (testing "clears due date when nil"
    (let [task (db/add-task *ds* nil "Another task")]
      (db/set-task-due-date *ds* nil (:id task) "2026-01-20")
      (let [result (db/set-task-due-date *ds* nil (:id task) nil)]
        (is (nil? (:due_date result)))))))

(deftest list-tasks-due-date-mode-filters-test
  (testing "due-date mode filters out tasks without due dates"
    (let [_task1 (db/add-task *ds* nil "No date")
          task2 (db/add-task *ds* nil "Has date")
          task3 (db/add-task *ds* nil "Also has date")]
      (db/set-task-due-date *ds* nil (:id task2) "2026-02-01")
      (db/set-task-due-date *ds* nil (:id task3) "2026-01-15")
      (let [tasks (db/list-tasks *ds* nil :due-date)]
        (is (= 2 (count tasks)))
        (is (not (some #(= "No date" (:title %)) tasks)))))))

(deftest list-tasks-due-date-mode-orders-test
  (testing "due-date mode orders by due date ascending"
    (let [task1 (db/add-task *ds* nil "Later")
          task2 (db/add-task *ds* nil "Earlier")
          task3 (db/add-task *ds* nil "Middle")]
      (db/set-task-due-date *ds* nil (:id task1) "2026-03-01")
      (db/set-task-due-date *ds* nil (:id task2) "2026-01-01")
      (db/set-task-due-date *ds* nil (:id task3) "2026-02-01")
      (let [tasks (db/list-tasks *ds* nil :due-date)]
        (is (= ["Earlier" "Middle" "Later"] (map :title tasks)))))))

(deftest delete-task-test
  (testing "deletes task and returns success"
    (let [task (db/add-task *ds* nil "To be deleted")
          result (db/delete-task *ds* nil (:id task))]
      (is (= true (:success result)))
      (is (= 0 (count (db/list-tasks *ds* nil))))))

  (testing "returns nil for non-existent task"
    (let [result (db/delete-task *ds* nil 99999)]
      (is (nil? result)))))

(deftest delete-task-with-categories-test
  (testing "deletes task and its categories"
    (let [task (db/add-task *ds* nil "Categorized task")
          person (db/add-person *ds* nil "Test Person")]
      (db/categorize-task *ds* nil (:id task) "person" (:id person))
      (is (= 1 (count (:people (first (db/list-tasks *ds* nil))))))
      (db/delete-task *ds* nil (:id task))
      (is (= 0 (count (db/list-tasks *ds* nil)))))))

(deftest user-data-isolation-test
  (testing "users see only their own data"
    (let [user2 (db/create-user *ds* "user2" "pass")
          user2-id (:id user2)]
      (db/add-task *ds* nil "Admin task")
      (db/add-task *ds* user2-id "User2 task")
      (db/add-person *ds* nil "Admin person")
      (db/add-person *ds* user2-id "User2 person")
      (is (= 1 (count (db/list-tasks *ds* nil))))
      (is (= 1 (count (db/list-tasks *ds* user2-id))))
      (is (= "Admin task" (:title (first (db/list-tasks *ds* nil)))))
      (is (= "User2 task" (:title (first (db/list-tasks *ds* user2-id)))))
      (is (= ["Admin person"] (map :name (db/list-people *ds* nil))))
      (is (= ["User2 person"] (map :name (db/list-people *ds* user2-id)))))))
