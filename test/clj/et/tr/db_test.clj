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

(deftest delete-user-cleans-up-data-test
  (testing "deleting user removes all their data"
    (let [user (db/create-user *ds* "testuser" "pass")
          user-id (:id user)
          task (db/add-task *ds* user-id "User task")
          person (db/add-person *ds* user-id "User person")
          place (db/add-place *ds* user-id "User place")
          project (db/add-project *ds* user-id "User project")
          goal (db/add-goal *ds* user-id "User goal")]
      (db/categorize-task *ds* user-id (:id task) "person" (:id person))
      (is (= 1 (count (db/list-tasks *ds* user-id))))
      (is (= 1 (count (db/list-people *ds* user-id))))
      (is (= 1 (count (db/list-places *ds* user-id))))
      (is (= 1 (count (db/list-projects *ds* user-id))))
      (is (= 1 (count (db/list-goals *ds* user-id))))
      (db/delete-user *ds* user-id)
      (is (= 0 (count (db/list-tasks *ds* user-id))))
      (is (= 0 (count (db/list-people *ds* user-id))))
      (is (= 0 (count (db/list-places *ds* user-id))))
      (is (= 0 (count (db/list-projects *ds* user-id))))
      (is (= 0 (count (db/list-goals *ds* user-id))))
      (is (nil? (db/get-user-by-username *ds* "testuser"))))))

(deftest set-task-done-test
  (testing "marks task as done"
    (let [task (db/add-task *ds* nil "Task to complete")
          result (db/set-task-done *ds* nil (:id task) true)]
      (is (= 1 (:done result)))
      (is (some? (:modified_at result)))))

  (testing "can unmark task as done"
    (let [task (db/add-task *ds* nil "Task to undo")
          _ (db/set-task-done *ds* nil (:id task) true)
          result (db/set-task-done *ds* nil (:id task) false)]
      (is (= 0 (:done result))))))

(deftest list-tasks-filters-done-test
  (testing "done tasks are hidden from manual, recent, and due-date modes"
    (let [task1 (db/add-task *ds* nil "Active task")
          task2 (db/add-task *ds* nil "Done task")
          task3 (db/add-task *ds* nil "Task with due")]
      (db/set-task-done *ds* nil (:id task2) true)
      (db/set-task-due-date *ds* nil (:id task1) "2026-02-01")
      (db/set-task-due-date *ds* nil (:id task3) "2026-03-01")
      (is (= 2 (count (db/list-tasks *ds* nil :manual))))
      (is (= 2 (count (db/list-tasks *ds* nil :recent))))
      (is (= 2 (count (db/list-tasks *ds* nil :due-date))))
      (is (not (some #(= "Done task" (:title %)) (db/list-tasks *ds* nil :manual))))
      (is (not (some #(= "Done task" (:title %)) (db/list-tasks *ds* nil :recent)))))))

(deftest list-tasks-done-mode-test
  (testing ":done mode shows only done tasks"
    (let [task1 (db/add-task *ds* nil "First done")
          task2 (db/add-task *ds* nil "Second done")
          _task3 (db/add-task *ds* nil "Not done")]
      (db/set-task-done *ds* nil (:id task1) true)
      (db/set-task-done *ds* nil (:id task2) true)
      (let [tasks (db/list-tasks *ds* nil :done)]
        (is (= 2 (count tasks)))
        (is (= #{"First done" "Second done"} (set (map :title tasks))))))))

(deftest update-task-updates-modified-at-test
  (testing "updating task returns modified_at"
    (let [task (db/add-task *ds* nil "Original")
          updated (db/update-task *ds* nil (:id task) "Updated" "Desc")]
      (is (some? (:modified_at updated))))))

(deftest set-due-date-updates-modified-at-test
  (testing "setting due date returns modified_at"
    (let [task (db/add-task *ds* nil "Task")
          result (db/set-task-due-date *ds* nil (:id task) "2026-05-01")]
      (is (some? (:modified_at result))))))

(deftest set-task-due-time-test
  (testing "sets due time on task"
    (let [task (db/add-task *ds* nil "Task with due time")]
      (db/set-task-due-date *ds* nil (:id task) "2026-01-15")
      (let [result (db/set-task-due-time *ds* nil (:id task) "14:30")]
        (is (= (:id task) (:id result)))
        (is (= "14:30" (:due_time result)))
        (is (= "2026-01-15" (:due_date result))))))

  (testing "clears due time when nil"
    (let [task (db/add-task *ds* nil "Another task")]
      (db/set-task-due-date *ds* nil (:id task) "2026-01-20")
      (db/set-task-due-time *ds* nil (:id task) "09:00")
      (let [result (db/set-task-due-time *ds* nil (:id task) nil)]
        (is (nil? (:due_time result)))
        (is (= "2026-01-20" (:due_date result))))))

  (testing "updates modified_at"
    (let [task (db/add-task *ds* nil "Task")]
      (db/set-task-due-date *ds* nil (:id task) "2026-01-15")
      (let [result (db/set-task-due-time *ds* nil (:id task) "10:00")]
        (is (some? (:modified_at result)))))))

(deftest set-due-date-clears-time-when-nil-test
  (testing "clearing due date also clears due time (prevents orphaned times)"
    (let [task (db/add-task *ds* nil "Task with date and time")]
      (db/set-task-due-date *ds* nil (:id task) "2026-01-15")
      (db/set-task-due-time *ds* nil (:id task) "14:30")
      (let [with-time (first (db/list-tasks *ds* nil))]
        (is (= "14:30" (:due_time with-time)))
        (is (= "2026-01-15" (:due_date with-time))))
      (let [result (db/set-task-due-date *ds* nil (:id task) nil)]
        (is (nil? (:due_date result)))
        (is (nil? (:due_time result))))))

  (testing "setting a new due date preserves existing time"
    (let [task (db/add-task *ds* nil "Task")]
      (db/set-task-due-date *ds* nil (:id task) "2026-01-15")
      (db/set-task-due-time *ds* nil (:id task) "14:30")
      (let [result (db/set-task-due-date *ds* nil (:id task) "2026-02-20")]
        (is (= "2026-02-20" (:due_date result)))
        (is (= "14:30" (:due_time result)))))))

(deftest list-tasks-includes-due-time-test
  (testing "list-tasks returns due_time field"
    (let [task (db/add-task *ds* nil "Task with time")]
      (db/set-task-due-date *ds* nil (:id task) "2026-01-15")
      (db/set-task-due-time *ds* nil (:id task) "09:30")
      (let [tasks (db/list-tasks *ds* nil)
            retrieved (first tasks)]
        (is (= "09:30" (:due_time retrieved)))
        (is (= "2026-01-15" (:due_date retrieved)))))))

(deftest category-name-unique-per-user-test
  (testing "different users can have categories with the same name"
    (let [user1 (db/create-user *ds* "user1" "pass1")
          user2 (db/create-user *ds* "user2" "pass2")
          user1-id (:id user1)
          user2-id (:id user2)]
      (db/add-person *ds* user1-id "John")
      (db/add-person *ds* user2-id "John")
      (db/add-place *ds* user1-id "Office")
      (db/add-place *ds* user2-id "Office")
      (db/add-project *ds* user1-id "Alpha")
      (db/add-project *ds* user2-id "Alpha")
      (db/add-goal *ds* user1-id "Launch")
      (db/add-goal *ds* user2-id "Launch")
      (is (= ["John"] (map :name (db/list-people *ds* user1-id))))
      (is (= ["John"] (map :name (db/list-people *ds* user2-id))))
      (is (= ["Office"] (map :name (db/list-places *ds* user1-id))))
      (is (= ["Office"] (map :name (db/list-places *ds* user2-id))))
      (is (= ["Alpha"] (map :name (db/list-projects *ds* user1-id))))
      (is (= ["Alpha"] (map :name (db/list-projects *ds* user2-id))))
      (is (= ["Launch"] (map :name (db/list-goals *ds* user1-id))))
      (is (= ["Launch"] (map :name (db/list-goals *ds* user2-id))))))

  (testing "same user cannot have duplicate category names"
    (let [user (db/create-user *ds* "testuser" "pass")
          user-id (:id user)]
      (db/add-person *ds* user-id "Alice")
      (is (thrown? Exception (db/add-person *ds* user-id "Alice"))))))

(deftest add-message-test
  (testing "adds message with required fields"
    (let [message (db/add-message *ds* nil "John" "Hello" "Test body")]
      (is (some? (:id message)))
      (is (= "John" (:sender message)))
      (is (= "Hello" (:title message)))
      (is (= "Test body" (:description message)))
      (is (= 0 (:done message)))
      (is (some? (:created_at message))))))

(deftest list-messages-empty-test
  (testing "returns empty list when no messages"
    (is (= [] (db/list-messages *ds* nil)))))

(deftest list-messages-recent-mode-test
  (testing "recent mode returns non-done messages"
    (let [m1 (db/add-message *ds* nil "A" "Msg1" "")
          _m2 (db/add-message *ds* nil "B" "Msg2" "")]
      (db/set-message-done *ds* nil (:id m1) true)
      (let [messages (db/list-messages *ds* nil :recent)]
        (is (= 1 (count messages)))
        (is (= "Msg2" (:title (first messages))))))))

(deftest list-messages-done-mode-test
  (testing "done mode returns archived messages"
    (let [m1 (db/add-message *ds* nil "A" "Msg1" "")
          _m2 (db/add-message *ds* nil "B" "Msg2" "")]
      (db/set-message-done *ds* nil (:id m1) true)
      (let [messages (db/list-messages *ds* nil :done)]
        (is (= 1 (count messages)))
        (is (= "Msg1" (:title (first messages))))))))

(deftest set-message-done-test
  (testing "marks message as done"
    (let [message (db/add-message *ds* nil "X" "Test" "")
          result (db/set-message-done *ds* nil (:id message) true)]
      (is (= 1 (:done result)))))

  (testing "can unmark message as done"
    (let [message (db/add-message *ds* nil "Y" "Test2" "")
          _ (db/set-message-done *ds* nil (:id message) true)
          result (db/set-message-done *ds* nil (:id message) false)]
      (is (= 0 (:done result))))))

(deftest delete-message-test
  (testing "deletes message and returns success"
    (let [message (db/add-message *ds* nil "Z" "ToDelete" "")
          result (db/delete-message *ds* nil (:id message))]
      (is (= true (:success result)))
      (is (= 0 (count (db/list-messages *ds* nil))))))

  (testing "returns nil for non-existent message"
    (let [result (db/delete-message *ds* nil 99999)]
      (is (nil? result)))))

(deftest message-user-isolation-test
  (testing "users see only their own messages"
    (let [user2 (db/create-user *ds* "user2" "pass")
          user2-id (:id user2)]
      (db/add-message *ds* nil "AdminSender" "Admin msg" "")
      (db/add-message *ds* user2-id "User2Sender" "User2 msg" "")
      (is (= 1 (count (db/list-messages *ds* nil))))
      (is (= 1 (count (db/list-messages *ds* user2-id))))
      (is (= "Admin msg" (:title (first (db/list-messages *ds* nil)))))
      (is (= "User2 msg" (:title (first (db/list-messages *ds* user2-id))))))))

(deftest delete-user-cleans-up-messages-test
  (testing "deleting user removes their messages"
    (let [user (db/create-user *ds* "testuser" "pass")
          user-id (:id user)]
      (db/add-message *ds* user-id "Sender" "User msg" "")
      (is (= 1 (count (db/list-messages *ds* user-id))))
      (db/delete-user *ds* user-id)
      (is (= 0 (count (db/list-messages *ds* user-id)))))))
