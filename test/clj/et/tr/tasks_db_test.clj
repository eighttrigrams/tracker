(ns et.tr.tasks-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db.task :as db.task]
            [et.tr.db.category :as db.category]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(deftest add-task-test
  (testing "adds task with title and returns it"
    (let [task (db.task/add-task *ds* *user-id* "Test task")]
      (is (some? (:id task)))
      (is (= "Test task" (:title task)))
      (is (= "" (:description task)))
      (is (some? (:created_at task)))
      (is (some? (:sort_order task))))))

(deftest add-task-sort-order-test
  (testing "new tasks get decreasing sort_order (appear at top)"
    (let [task1 (db.task/add-task *ds* *user-id* "First")
          task2 (db.task/add-task *ds* *user-id* "Second")
          task3 (db.task/add-task *ds* *user-id* "Third")]
      (is (< (:sort_order task3) (:sort_order task2)))
      (is (< (:sort_order task2) (:sort_order task1))))))

(deftest list-tasks-empty-test
  (testing "returns empty list when no tasks"
    (is (= [] (db.task/list-tasks *ds* *user-id*)))))

(deftest list-tasks-with-categories-test
  (testing "returns tasks with categories"
    (let [task (db.task/add-task *ds* *user-id* "Task with categories")
          person (db.category/add-person *ds* *user-id* "Alice")]
      (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id person))
      (let [tasks (db.task/list-tasks *ds* *user-id*)
            retrieved (first tasks)]
        (is (= 1 (count tasks)))
        (is (= "Task with categories" (:title retrieved)))
        (is (= [{:id (:id person) :name "Alice" :badge_title ""}] (:people retrieved)))
        (is (= [] (:places retrieved)))
        (is (= [] (:projects retrieved)))
        (is (= [] (:goals retrieved)))))))

(deftest list-tasks-recent-mode-test
  (testing "recent mode returns all tasks"
    (db.task/add-task *ds* *user-id* "First")
    (db.task/add-task *ds* *user-id* "Second")
    (db.task/add-task *ds* *user-id* "Third")
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent)]
      (is (= 3 (count tasks)))
      (is (= #{"First" "Second" "Third"} (set (map :title tasks)))))))

(deftest list-tasks-manual-mode-test
  (testing "manual mode orders by sort_order ASC"
    (db.task/add-task *ds* *user-id* "First")
    (Thread/sleep 10)
    (db.task/add-task *ds* *user-id* "Second")
    (Thread/sleep 10)
    (db.task/add-task *ds* *user-id* "Third")
    (let [tasks (db.task/list-tasks *ds* *user-id* :manual)]
      (is (= ["Third" "Second" "First"] (map :title tasks))))))

(deftest reorder-task-updates-sort-order-test
  (testing "updates task sort_order"
    (let [task (db.task/add-task *ds* *user-id* "Test")
          result (db.task/reorder-task *ds* *user-id* (:id task) 99.5)]
      (is (= true (:success result)))
      (is (= 99.5 (:sort_order result)))
      (is (= 99.5 (db.task/get-task-sort-order *ds* *user-id* (:id task)))))))

(deftest reorder-task-changes-position-test
  (testing "reordering changes position in manual mode"
    (let [task1 (db.task/add-task *ds* *user-id* "A")
          _task2 (db.task/add-task *ds* *user-id* "B")
          task3 (db.task/add-task *ds* *user-id* "C")
          initial-order (map :title (db.task/list-tasks *ds* *user-id* :manual))]
      (is (= ["C" "B" "A"] initial-order))
      (db.task/reorder-task *ds* *user-id* (:id task1) (- (:sort_order task3) 0.5))
      (let [new-order (map :title (db.task/list-tasks *ds* *user-id* :manual))]
        (is (= ["A" "C" "B"] new-order))))))

(deftest get-task-sort-order-test
  (testing "returns sort_order for task"
    (let [task (db.task/add-task *ds* *user-id* "Test")]
      (is (= (:sort_order task) (db.task/get-task-sort-order *ds* *user-id* (:id task))))))

  (testing "returns nil for non-existent task"
    (is (nil? (db.task/get-task-sort-order *ds* *user-id* 99999)))))

(deftest update-task-test
  (testing "updates title and description"
    (let [task (db.task/add-task *ds* *user-id* "Original")
          updated (db.task/update-task *ds* *user-id* (:id task) {:title "Updated" :description "New description"})]
      (is (= "Updated" (:title updated)))
      (is (= "New description" (:description updated)))
      (is (= (:id task) (:id updated))))))

(deftest categorize-task-test
  (testing "can categorize task with person, place, project, goal"
    (let [task (db.task/add-task *ds* *user-id* "Task")
          person (db.category/add-person *ds* *user-id* "Bob")
          place (db.category/add-place *ds* *user-id* "Office")
          project (db.category/add-project *ds* *user-id* "Website")
          goal (db.category/add-goal *ds* *user-id* "Launch")]
      (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id person))
      (db.task/categorize-task *ds* *user-id* (:id task) "place" (:id place))
      (db.task/categorize-task *ds* *user-id* (:id task) "project" (:id project))
      (db.task/categorize-task *ds* *user-id* (:id task) "goal" (:id goal))
      (let [tasks (db.task/list-tasks *ds* *user-id*)
            categorized (first tasks)]
        (is (= 1 (count (:people categorized))))
        (is (= 1 (count (:places categorized))))
        (is (= 1 (count (:projects categorized))))
        (is (= 1 (count (:goals categorized))))))))

(deftest uncategorize-task-test
  (testing "can uncategorize task"
    (let [task (db.task/add-task *ds* *user-id* "Task")
          person (db.category/add-person *ds* *user-id* "Carol")]
      (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id person))
      (is (= 1 (count (:people (first (db.task/list-tasks *ds* *user-id*))))))
      (db.task/uncategorize-task *ds* *user-id* (:id task) "person" (:id person))
      (is (= 0 (count (:people (first (db.task/list-tasks *ds* *user-id*)))))))))

(deftest sort-order-midpoint-test
  (testing "midpoint insertion maintains order"
    (let [task1 (db.task/add-task *ds* *user-id* "A")
          task2 (db.task/add-task *ds* *user-id* "B")
          task3 (db.task/add-task *ds* *user-id* "C")
          order1 (:sort_order task1)
          order2 (:sort_order task2)
          midpoint (/ (+ order1 order2) 2.0)]
      (db.task/reorder-task *ds* *user-id* (:id task3) midpoint)
      (let [tasks (db.task/list-tasks *ds* *user-id* :manual)]
        (is (= ["B" "C" "A"] (map :title tasks)))))))

(deftest set-task-due-date-test
  (testing "sets due date on task"
    (let [task (db.task/add-task *ds* *user-id* "Task with due date")
          result (db.task/set-task-due-date *ds* *user-id* (:id task) "2026-01-15")]
      (is (= (:id task) (:id result)))
      (is (= "2026-01-15" (:due_date result)))))

  (testing "clears due date when nil"
    (let [task (db.task/add-task *ds* *user-id* "Another task")]
      (db.task/set-task-due-date *ds* *user-id* (:id task) "2026-01-20")
      (let [result (db.task/set-task-due-date *ds* *user-id* (:id task) nil)]
        (is (nil? (:due_date result)))))))

(deftest list-tasks-due-date-mode-filters-test
  (testing "due-date mode filters out tasks without due dates"
    (let [_task1 (db.task/add-task *ds* *user-id* "No date")
          task2 (db.task/add-task *ds* *user-id* "Has date")
          task3 (db.task/add-task *ds* *user-id* "Also has date")]
      (db.task/set-task-due-date *ds* *user-id* (:id task2) "2026-02-01")
      (db.task/set-task-due-date *ds* *user-id* (:id task3) "2026-01-15")
      (let [tasks (db.task/list-tasks *ds* *user-id* :due-date)]
        (is (= 2 (count tasks)))
        (is (not (some #(= "No date" (:title %)) tasks)))))))

(deftest list-tasks-due-date-mode-orders-test
  (testing "due-date mode orders by due date ascending"
    (let [task1 (db.task/add-task *ds* *user-id* "Later")
          task2 (db.task/add-task *ds* *user-id* "Earlier")
          task3 (db.task/add-task *ds* *user-id* "Middle")]
      (db.task/set-task-due-date *ds* *user-id* (:id task1) "2026-03-01")
      (db.task/set-task-due-date *ds* *user-id* (:id task2) "2026-01-01")
      (db.task/set-task-due-date *ds* *user-id* (:id task3) "2026-02-01")
      (let [tasks (db.task/list-tasks *ds* *user-id* :due-date)]
        (is (= ["Earlier" "Middle" "Later"] (map :title tasks)))))))

(deftest delete-task-test
  (testing "deletes task and returns success"
    (let [task (db.task/add-task *ds* *user-id* "To be deleted")
          result (db.task/delete-task *ds* *user-id* (:id task))]
      (is (= true (:success result)))
      (is (= 0 (count (db.task/list-tasks *ds* *user-id*))))))

  (testing "returns nil for non-existent task"
    (let [result (db.task/delete-task *ds* *user-id* 99999)]
      (is (nil? result)))))

(deftest delete-task-with-categories-test
  (testing "deletes task and its categories"
    (let [task (db.task/add-task *ds* *user-id* "Categorized task")
          person (db.category/add-person *ds* *user-id* "Test Person")]
      (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id person))
      (is (= 1 (count (:people (first (db.task/list-tasks *ds* *user-id*))))))
      (db.task/delete-task *ds* *user-id* (:id task))
      (is (= 0 (count (db.task/list-tasks *ds* *user-id*)))))))

(deftest set-task-done-test
  (testing "marks task as done"
    (let [task (db.task/add-task *ds* *user-id* "Task to complete")
          result (db.task/set-task-done *ds* *user-id* (:id task) true)]
      (is (= 1 (:done result)))
      (is (some? (:modified_at result)))))

  (testing "can unmark task as done"
    (let [task (db.task/add-task *ds* *user-id* "Task to undo")
          _ (db.task/set-task-done *ds* *user-id* (:id task) true)
          result (db.task/set-task-done *ds* *user-id* (:id task) false)]
      (is (= 0 (:done result))))))

(deftest list-tasks-filters-done-test
  (testing "done tasks are hidden from manual, recent, and due-date modes"
    (let [task1 (db.task/add-task *ds* *user-id* "Active task")
          task2 (db.task/add-task *ds* *user-id* "Done task")
          task3 (db.task/add-task *ds* *user-id* "Task with due")]
      (db.task/set-task-done *ds* *user-id* (:id task2) true)
      (db.task/set-task-due-date *ds* *user-id* (:id task1) "2026-02-01")
      (db.task/set-task-due-date *ds* *user-id* (:id task3) "2026-03-01")
      (is (= 2 (count (db.task/list-tasks *ds* *user-id* :manual))))
      (is (= 2 (count (db.task/list-tasks *ds* *user-id* :recent))))
      (is (= 2 (count (db.task/list-tasks *ds* *user-id* :due-date))))
      (is (not (some #(= "Done task" (:title %)) (db.task/list-tasks *ds* *user-id* :manual))))
      (is (not (some #(= "Done task" (:title %)) (db.task/list-tasks *ds* *user-id* :recent)))))))

(deftest list-tasks-done-mode-test
  (testing ":done mode shows only done tasks"
    (let [task1 (db.task/add-task *ds* *user-id* "First done")
          task2 (db.task/add-task *ds* *user-id* "Second done")
          _task3 (db.task/add-task *ds* *user-id* "Not done")]
      (db.task/set-task-done *ds* *user-id* (:id task1) true)
      (db.task/set-task-done *ds* *user-id* (:id task2) true)
      (let [tasks (db.task/list-tasks *ds* *user-id* :done)]
        (is (= 2 (count tasks)))
        (is (= #{"First done" "Second done"} (set (map :title tasks))))))))

(deftest update-task-updates-modified-at-test
  (testing "updating task returns modified_at"
    (let [task (db.task/add-task *ds* *user-id* "Original")
          updated (db.task/update-task *ds* *user-id* (:id task) {:title "Updated" :description "Desc"})]
      (is (some? (:modified_at updated))))))

(deftest set-due-date-updates-modified-at-test
  (testing "setting due date returns modified_at"
    (let [task (db.task/add-task *ds* *user-id* "Task")
          result (db.task/set-task-due-date *ds* *user-id* (:id task) "2026-05-01")]
      (is (some? (:modified_at result))))))

(deftest set-task-due-time-test
  (testing "sets due time on task"
    (let [task (db.task/add-task *ds* *user-id* "Task with due time")]
      (db.task/set-task-due-date *ds* *user-id* (:id task) "2026-01-15")
      (let [result (db.task/set-task-due-time *ds* *user-id* (:id task) "14:30")]
        (is (= (:id task) (:id result)))
        (is (= "14:30" (:due_time result)))
        (is (= "2026-01-15" (:due_date result))))))

  (testing "clears due time when nil"
    (let [task (db.task/add-task *ds* *user-id* "Another task")]
      (db.task/set-task-due-date *ds* *user-id* (:id task) "2026-01-20")
      (db.task/set-task-due-time *ds* *user-id* (:id task) "09:00")
      (let [result (db.task/set-task-due-time *ds* *user-id* (:id task) nil)]
        (is (nil? (:due_time result)))
        (is (= "2026-01-20" (:due_date result))))))

  (testing "updates modified_at"
    (let [task (db.task/add-task *ds* *user-id* "Task")]
      (db.task/set-task-due-date *ds* *user-id* (:id task) "2026-01-15")
      (let [result (db.task/set-task-due-time *ds* *user-id* (:id task) "10:00")]
        (is (some? (:modified_at result)))))))

(deftest set-due-date-clears-time-when-nil-test
  (testing "clearing due date also clears due time (prevents orphaned times)"
    (let [task (db.task/add-task *ds* *user-id* "Task with date and time")]
      (db.task/set-task-due-date *ds* *user-id* (:id task) "2026-01-15")
      (db.task/set-task-due-time *ds* *user-id* (:id task) "14:30")
      (let [with-time (first (db.task/list-tasks *ds* *user-id*))]
        (is (= "14:30" (:due_time with-time)))
        (is (= "2026-01-15" (:due_date with-time))))
      (let [result (db.task/set-task-due-date *ds* *user-id* (:id task) nil)]
        (is (nil? (:due_date result)))
        (is (nil? (:due_time result))))))

  (testing "setting a new due date preserves existing time"
    (let [task (db.task/add-task *ds* *user-id* "Task")]
      (db.task/set-task-due-date *ds* *user-id* (:id task) "2026-01-15")
      (db.task/set-task-due-time *ds* *user-id* (:id task) "14:30")
      (let [result (db.task/set-task-due-date *ds* *user-id* (:id task) "2026-02-20")]
        (is (= "2026-02-20" (:due_date result)))
        (is (= "14:30" (:due_time result)))))))

(deftest list-tasks-includes-due-time-test
  (testing "list-tasks returns due_time field"
    (let [task (db.task/add-task *ds* *user-id* "Task with time")]
      (db.task/set-task-due-date *ds* *user-id* (:id task) "2026-01-15")
      (db.task/set-task-due-time *ds* *user-id* (:id task) "09:30")
      (let [tasks (db.task/list-tasks *ds* *user-id*)
            retrieved (first tasks)]
        (is (= "09:30" (:due_time retrieved)))
        (is (= "2026-01-15" (:due_date retrieved)))))))

(deftest list-tasks-search-test
  (testing "nil search-term returns all tasks"
    (db.task/add-task *ds* *user-id* "Apple pie")
    (db.task/add-task *ds* *user-id* "Banana bread")
    (db.task/add-task *ds* *user-id* "Cherry cake")
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent nil)]
      (is (= 3 (count tasks)))))

  (testing "empty search-term returns all tasks"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent "")]
      (is (= 3 (count tasks)))))

  (testing "search matches title starting with term"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent "app")]
      (is (= 1 (count tasks)))
      (is (= "Apple pie" (:title (first tasks))))))

  (testing "search matches word starting with term"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent "pie")]
      (is (= 1 (count tasks)))
      (is (= "Apple pie" (:title (first tasks))))))

  (testing "search is case-insensitive"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent "BANANA")]
      (is (= 1 (count tasks)))
      (is (= "Banana bread" (:title (first tasks))))))

  (testing "search trims whitespace"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent "  cherry  ")]
      (is (= 1 (count tasks)))
      (is (= "Cherry cake" (:title (first tasks))))))

  (testing "search works with all sort modes"
    (is (= 1 (count (db.task/list-tasks *ds* *user-id* :manual "apple"))))
    (is (= 1 (count (db.task/list-tasks *ds* *user-id* :recent "apple")))))

  (testing "search with no matches returns empty"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent "xyz")]
      (is (= 0 (count tasks))))))

(deftest list-tasks-multi-prefix-search-test
  (testing "setup data for multi-prefix tests"
    (db.task/add-task *ds* *user-id* "aaaba cccde")
    (db.task/add-task *ds* *user-id* "kkml aka")
    (db.task/add-task *ds* *user-id* "aaa alba"))

  (testing "single prefix 'aaa' matches tasks 1 and 3"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent "aaa")]
      (is (= 2 (count tasks)))
      (is (= #{"aaaba cccde" "aaa alba"} (set (map :title tasks))))))

  (testing "two prefixes 'aaa al' narrows to task 3 only"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent "aaa al")]
      (is (= 1 (count tasks)))
      (is (= "aaa alba" (:title (first tasks))))))

  (testing "prefix order independence - 'al aaa' same as 'aaa al'"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent "al aaa")]
      (is (= 1 (count tasks)))
      (is (= "aaa alba" (:title (first tasks))))))

  (testing "extra whitespace is handled"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent "  aaa   al  ")]
      (is (= 1 (count tasks)))
      (is (= "aaa alba" (:title (first tasks))))))

  (testing "case insensitivity with multiple prefixes"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent "AAA AL")]
      (is (= 1 (count tasks)))
      (is (= "aaa alba" (:title (first tasks))))))

  (testing "three+ prefixes narrow further"
    (db.task/add-task *ds* *user-id* "aaa alba xyz")
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent "aaa al xyz")]
      (is (= 1 (count tasks)))
      (is (= "aaa alba xyz" (:title (first tasks))))))

  (testing "no match when one prefix fails"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent "aaa nonexistent")]
      (is (= 0 (count tasks))))))

(deftest list-tasks-importance-filter-test
  (testing "no importance filter returns all tasks"
    (let [_task1 (db.task/add-task *ds* *user-id* "Normal task")
          task2 (db.task/add-task *ds* *user-id* "Important task")
          task3 (db.task/add-task *ds* *user-id* "Critical task")]
      (db.task/set-task-field *ds* *user-id* (:id task2) :importance "important")
      (db.task/set-task-field *ds* *user-id* (:id task3) :importance "critical")
      (let [tasks (db.task/list-tasks *ds* *user-id* :recent {})]
        (is (= 3 (count tasks))))))

  (testing "important filter returns important and critical tasks"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:importance "important"})]
      (is (= 2 (count tasks)))
      (is (= #{"Important task" "Critical task"} (set (map :title tasks))))))

  (testing "critical filter returns only critical tasks"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:importance "critical"})]
      (is (= 1 (count tasks)))
      (is (= "Critical task" (:title (first tasks))))))

  (testing "importance filter works with search"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:search-term "task" :importance "important"})]
      (is (= 2 (count tasks)))
      (is (not (some #(= "Normal task" (:title %)) tasks))))))

(deftest list-tasks-today-mode-test
  (testing "today mode returns tasks with due dates"
    (let [task1 (db.task/add-task *ds* *user-id* "Has due date")
          _task2 (db.task/add-task *ds* *user-id* "No due date")]
      (db.task/set-task-due-date *ds* *user-id* (:id task1) "2026-02-01")
      (let [tasks (db.task/list-tasks *ds* *user-id* :today)]
        (is (some #(= "Has due date" (:title %)) tasks))
        (is (not (some #(= "No due date" (:title %)) tasks))))))

  (testing "today mode returns urgent and superurgent tasks without due dates"
    (let [_task1 (db.task/add-task *ds* *user-id* "Normal urgency")
          task2 (db.task/add-task *ds* *user-id* "Urgent task")
          task3 (db.task/add-task *ds* *user-id* "Superurgent task")]
      (db.task/set-task-field *ds* *user-id* (:id task2) :urgency "urgent")
      (db.task/set-task-field *ds* *user-id* (:id task3) :urgency "superurgent")
      (let [tasks (db.task/list-tasks *ds* *user-id* :today)]
        (is (some #(= "Urgent task" (:title %)) tasks))
        (is (some #(= "Superurgent task" (:title %)) tasks))
        (is (not (some #(= "Normal urgency" (:title %)) tasks)))))))

(deftest list-tasks-context-filter-test
  (testing "no context filter returns all tasks"
    (let [_task1 (db.task/add-task *ds* *user-id* "Private task" "private")
          _task2 (db.task/add-task *ds* *user-id* "Work task" "work")
          _task3 (db.task/add-task *ds* *user-id* "Both task" "both")
          tasks (db.task/list-tasks *ds* *user-id* :recent {})]
      (is (= 3 (count tasks)))))

  (testing "private context (non-strict) returns private and both"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:context "private"})]
      (is (= 2 (count tasks)))
      (is (some #(= "Private task" (:title %)) tasks))
      (is (some #(= "Both task" (:title %)) tasks))
      (is (not (some #(= "Work task" (:title %)) tasks)))))

  (testing "work context (non-strict) returns work and both"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:context "work"})]
      (is (= 2 (count tasks)))
      (is (some #(= "Work task" (:title %)) tasks))
      (is (some #(= "Both task" (:title %)) tasks))
      (is (not (some #(= "Private task" (:title %)) tasks)))))

  (testing "strict mode private returns only private"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:context "private" :strict true})]
      (is (= 1 (count tasks)))
      (is (= "Private task" (:title (first tasks))))))

  (testing "strict mode work returns only work"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:context "work" :strict true})]
      (is (= 1 (count tasks)))
      (is (= "Work task" (:title (first tasks))))))

  (testing "strict mode both returns only both"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:context "both" :strict true})]
      (is (= 1 (count tasks)))
      (is (= "Both task" (:title (first tasks))))))

  (testing "context filter works with search"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:search-term "task" :context "private"})]
      (is (= 2 (count tasks)))
      (is (not (some #(= "Work task" (:title %)) tasks))))))

(deftest list-tasks-tag-search-test
  (testing "search matches tags field"
    (let [task1 (db.task/add-task *ds* *user-id* "aaaba bbbd kka")
          task2 (db.task/add-task *ds* *user-id* "eieie yoyo")
          task3 (db.task/add-task *ds* *user-id* "aaa mmm")]
      (db.task/update-task *ds* *user-id* (:id task1) {:title "aaaba bbbd kka" :description "" :tags "cool"})
      (db.task/update-task *ds* *user-id* (:id task2) {:title "eieie yoyo" :description "" :tags "sup"})
      (db.task/update-task *ds* *user-id* (:id task3) {:title "aaa mmm" :description "" :tags "alma bb"})
      (let [tasks (db.task/list-tasks *ds* *user-id* :recent "aa bb")]
        (is (= 2 (count tasks)))
        (is (= #{"aaaba bbbd kka" "aaa mmm"} (set (map :title tasks)))))))

  (testing "tag search is case insensitive"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent "COOL")]
      (is (= 1 (count tasks)))
      (is (= "aaaba bbbd kka" (:title (first tasks))))))

  (testing "search matches prefix in tags"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent "al")]
      (is (= 1 (count tasks)))
      (is (= "aaa mmm" (:title (first tasks)))))))

(deftest list-tasks-category-filter-test
  (testing "single category filter returns matching tasks"
    (let [task1 (db.task/add-task *ds* *user-id* "Task with Alice")
          _task2 (db.task/add-task *ds* *user-id* "Task without person")
          person (db.category/add-person *ds* *user-id* "Alice")]
      (db.task/categorize-task *ds* *user-id* (:id task1) "person" (:id person))
      (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:categories {:people ["Alice"]}})]
        (is (= 1 (count tasks)))
        (is (= "Task with Alice" (:title (first tasks)))))))

  (testing "OR logic within same category group"
    (let [task1 (db.task/add-task *ds* *user-id* "Task with Alice")
          task2 (db.task/add-task *ds* *user-id* "Task with Bob")
          _task3 (db.task/add-task *ds* *user-id* "Task with nobody")
          alice (db.category/add-person *ds* *user-id* "Alice2")
          bob (db.category/add-person *ds* *user-id* "Bob")]
      (db.task/categorize-task *ds* *user-id* (:id task1) "person" (:id alice))
      (db.task/categorize-task *ds* *user-id* (:id task2) "person" (:id bob))
      (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:categories {:people ["Alice2" "Bob"]}})]
        (is (= 2 (count tasks)))
        (is (= #{"Task with Alice" "Task with Bob"} (set (map :title tasks)))))))

  (testing "non-matching category returns no tasks"
    (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:categories {:people ["NonExistent"]}})]
      (is (= 0 (count tasks))))))

(deftest list-tasks-category-filter-and-logic-test
  (testing "AND logic across category groups"
    (let [task1 (db.task/add-task *ds* *user-id* "Task with Alice and Office")
          task2 (db.task/add-task *ds* *user-id* "Task with Alice only")
          task3 (db.task/add-task *ds* *user-id* "Task with Office only")
          alice (db.category/add-person *ds* *user-id* "Alice3")
          office (db.category/add-place *ds* *user-id* "Office")]
      (db.task/categorize-task *ds* *user-id* (:id task1) "person" (:id alice))
      (db.task/categorize-task *ds* *user-id* (:id task1) "place" (:id office))
      (db.task/categorize-task *ds* *user-id* (:id task2) "person" (:id alice))
      (db.task/categorize-task *ds* *user-id* (:id task3) "place" (:id office))
      (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:categories {:people ["Alice3"] :places ["Office"]}})]
        (is (= 1 (count tasks)))
        (is (= "Task with Alice and Office" (:title (first tasks))))))))

(deftest list-tasks-category-filter-all-types-test
  (testing "all four category types work"
    (let [task1 (db.task/add-task *ds* *user-id* "Full task")
          _task2 (db.task/add-task *ds* *user-id* "Empty task")
          person (db.category/add-person *ds* *user-id* "PersonAll")
          place (db.category/add-place *ds* *user-id* "PlaceAll")
          project (db.category/add-project *ds* *user-id* "ProjectAll")
          goal (db.category/add-goal *ds* *user-id* "GoalAll")]
      (db.task/categorize-task *ds* *user-id* (:id task1) "person" (:id person))
      (db.task/categorize-task *ds* *user-id* (:id task1) "place" (:id place))
      (db.task/categorize-task *ds* *user-id* (:id task1) "project" (:id project))
      (db.task/categorize-task *ds* *user-id* (:id task1) "goal" (:id goal))
      (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:categories {:people ["PersonAll"]
                                                                 :places ["PlaceAll"]
                                                                 :projects ["ProjectAll"]
                                                                 :goals ["GoalAll"]}})]
        (is (= 1 (count tasks)))
        (is (= "Full task" (:title (first tasks))))))))

(deftest list-tasks-category-filter-with-other-filters-test
  (testing "category filter works with search"
    (let [task1 (db.task/add-task *ds* *user-id* "Alpha task")
          task2 (db.task/add-task *ds* *user-id* "Beta task")
          person (db.category/add-person *ds* *user-id* "TestPerson")]
      (db.task/categorize-task *ds* *user-id* (:id task1) "person" (:id person))
      (db.task/categorize-task *ds* *user-id* (:id task2) "person" (:id person))
      (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:search-term "Alpha" :categories {:people ["TestPerson"]}})]
        (is (= 1 (count tasks)))
        (is (= "Alpha task" (:title (first tasks)))))))

  (testing "category filter works with importance"
    (let [task1 (db.task/add-task *ds* *user-id* "Important task")
          task2 (db.task/add-task *ds* *user-id* "Normal task")
          person (db.category/add-person *ds* *user-id* "TestPerson2")]
      (db.task/set-task-field *ds* *user-id* (:id task1) :importance "important")
      (db.task/categorize-task *ds* *user-id* (:id task1) "person" (:id person))
      (db.task/categorize-task *ds* *user-id* (:id task2) "person" (:id person))
      (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:importance "important" :categories {:people ["TestPerson2"]}})]
        (is (= 1 (count tasks)))
        (is (= "Important task" (:title (first tasks)))))))

  (testing "category filter works with context"
    (let [task1 (db.task/add-task *ds* *user-id* "Private task" "private")
          task2 (db.task/add-task *ds* *user-id* "Work task" "work")
          person (db.category/add-person *ds* *user-id* "TestPerson3")]
      (db.task/categorize-task *ds* *user-id* (:id task1) "person" (:id person))
      (db.task/categorize-task *ds* *user-id* (:id task2) "person" (:id person))
      (let [tasks (db.task/list-tasks *ds* *user-id* :recent {:context "private" :categories {:people ["TestPerson3"]}})]
        (is (= 1 (count tasks)))
        (is (= "Private task" (:title (first tasks)))))))

  (testing "category filter works with urgency in today mode"
    (let [task1 (db.task/add-task *ds* *user-id* "Urgent task")
          task2 (db.task/add-task *ds* *user-id* "Not urgent task")
          person (db.category/add-person *ds* *user-id* "TestPerson4")]
      (db.task/set-task-field *ds* *user-id* (:id task1) :urgency "urgent")
      (db.task/categorize-task *ds* *user-id* (:id task1) "person" (:id person))
      (db.task/categorize-task *ds* *user-id* (:id task2) "person" (:id person))
      (let [tasks (db.task/list-tasks *ds* *user-id* :today {:categories {:people ["TestPerson4"]}})]
        (is (= 1 (count tasks)))
        (is (= "Urgent task" (:title (first tasks))))))))

(deftest list-tasks-exclusion-filter-test
  (testing "excluded place filters out tasks with that place"
    (let [task1 (db.task/add-task *ds* *user-id* "Home task")
          task2 (db.task/add-task *ds* *user-id* "Office task")
          task3 (db.task/add-task *ds* *user-id* "No place task")
          home (db.category/add-place *ds* *user-id* "Home")
          office (db.category/add-place *ds* *user-id* "Office")]
      (db.task/set-task-due-date *ds* *user-id* (:id task1) "2026-03-01")
      (db.task/set-task-due-date *ds* *user-id* (:id task2) "2026-03-02")
      (db.task/set-task-due-date *ds* *user-id* (:id task3) "2026-03-03")
      (db.task/categorize-task *ds* *user-id* (:id task1) "place" (:id home))
      (db.task/categorize-task *ds* *user-id* (:id task2) "place" (:id office))
      (let [tasks (db.task/list-tasks *ds* *user-id* :today {:excluded-places ["Home"]})]
        (is (= 2 (count tasks)))
        (is (not (some #(= "Home task" (:title %)) tasks)))
        (is (some #(= "Office task" (:title %)) tasks))
        (is (some #(= "No place task" (:title %)) tasks)))))

  (testing "excluded project filters out tasks with that project"
    (let [task1 (db.task/add-task *ds* *user-id* "Alpha task")
          task2 (db.task/add-task *ds* *user-id* "Beta task")
          alpha (db.category/add-project *ds* *user-id* "Alpha")
          _beta (db.category/add-project *ds* *user-id* "Beta")]
      (db.task/set-task-due-date *ds* *user-id* (:id task1) "2026-03-01")
      (db.task/set-task-due-date *ds* *user-id* (:id task2) "2026-03-02")
      (db.task/categorize-task *ds* *user-id* (:id task1) "project" (:id alpha))
      (let [tasks (db.task/list-tasks *ds* *user-id* :today {:excluded-projects ["Alpha"]})]
        (is (not (some #(= "Alpha task" (:title %)) tasks)))
        (is (some #(= "Beta task" (:title %)) tasks))))))

(deftest list-tasks-exclusion-filter-combined-test
  (testing "excluded places and projects combine"
    (let [task1 (db.task/add-task *ds* *user-id* "Home Alpha")
          task2 (db.task/add-task *ds* *user-id* "Office Beta")
          task3 (db.task/add-task *ds* *user-id* "Plain task")
          home (db.category/add-place *ds* *user-id* "HomeX")
          alpha (db.category/add-project *ds* *user-id* "AlphaX")]
      (db.task/set-task-due-date *ds* *user-id* (:id task1) "2026-03-01")
      (db.task/set-task-due-date *ds* *user-id* (:id task2) "2026-03-02")
      (db.task/set-task-due-date *ds* *user-id* (:id task3) "2026-03-03")
      (db.task/categorize-task *ds* *user-id* (:id task1) "place" (:id home))
      (db.task/categorize-task *ds* *user-id* (:id task2) "project" (:id alpha))
      (let [tasks (db.task/list-tasks *ds* *user-id* :today {:excluded-places ["HomeX"] :excluded-projects ["AlphaX"]})]
        (is (= 1 (count tasks)))
        (is (= "Plain task" (:title (first tasks)))))))

  (testing "tasks without categories are never excluded"
    (let [task1 (db.task/add-task *ds* *user-id* "Unassigned")]
      (db.task/set-task-due-date *ds* *user-id* (:id task1) "2026-03-01")
      (let [tasks (db.task/list-tasks *ds* *user-id* :today {:excluded-places ["NonExistent"] :excluded-projects ["NonExistent"]})]
        (is (some #(= "Unassigned" (:title %)) tasks))))))
