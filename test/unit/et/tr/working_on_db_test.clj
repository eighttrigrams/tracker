(ns et.tr.working-on-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.tr.clock :as clock]
            [et.tr.db :as db]
            [et.tr.db.task :as db.task]
            [et.tr.db.user :as db.user]
            [et.tr.db.working-on :as db.working-on]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(defn- stamp-set-on! [user-id date]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:update :working_on
                 :set {:set_on date}
                 :where [:= :user_id user-id]})))

;; Not db.task/set-task-done: that clears the marker, and the point is a marker
;; that outlived its task being done — the shape a row written before the
;; doneness clause existed still has.
(defn- stamp-task-done! [task-id]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:update :tasks
                 :set {:done 1}
                 :where [:= :id task-id]})))

(deftest no-marker-by-default-test
  (testing "a user with no row is working on nothing"
    (is (nil? (db.working-on/get-working-on *ds* *user-id*)))))

(deftest set-working-on-round-trips-test
  (testing "the marker reads back with today's stamp"
    (let [task (db.task/add-task *ds* *user-id* "Write the thing")
          result (db.working-on/set-working-on! *ds* *user-id* (:id task))]
      (is (= {:task-id (:id task) :set-on (clock/today-str)} result))
      (is (= result (db.working-on/get-working-on *ds* *user-id*))))))

(deftest set-working-on-is-a-singleton-test
  (testing "setting it on a second task replaces the first"
    (let [task1 (db.task/add-task *ds* *user-id* "First")
          task2 (db.task/add-task *ds* *user-id* "Second")]
      (db.working-on/set-working-on! *ds* *user-id* (:id task1))
      (db.working-on/set-working-on! *ds* *user-id* (:id task2))
      (is (= (:id task2) (:task-id (db.working-on/get-working-on *ds* *user-id*))))
      (is (= 1 (:cnt (jdbc/execute-one! (db/get-conn *ds*)
                       (sql/format {:select [[[:count :*] :cnt]] :from [:working_on]})
                       db/jdbc-opts)))))))

(deftest set-working-on-refuses-unknown-task-test
  (testing "an id that is no task at all is refused and stores nothing"
    (is (nil? (db.working-on/set-working-on! *ds* *user-id* 99999)))
    (is (nil? (db.working-on/get-working-on *ds* *user-id*)))))

(deftest set-working-on-refuses-foreign-task-test
  (testing "another user's task is refused"
    (let [other (db.user/create-user *ds* "other-user" "testpass")
          their-task (db.task/add-task *ds* (:id other) "Not yours")]
      (is (nil? (db.working-on/set-working-on! *ds* *user-id* (:id their-task))))
      (is (nil? (db.working-on/get-working-on *ds* *user-id*))))))

(deftest set-working-on-refuses-done-task-test
  (testing "an already-done task is refused and stores nothing"
    (let [task (db.task/add-task *ds* *user-id* "Already finished")]
      (db.task/set-task-done *ds* *user-id* (:id task) true)
      (is (nil? (db.working-on/set-working-on! *ds* *user-id* (:id task))))
      (is (nil? (db.working-on/get-working-on *ds* *user-id*)))
      (is (= 0 (:cnt (jdbc/execute-one! (db/get-conn *ds*)
                       (sql/format {:select [[[:count :*] :cnt]] :from [:working_on]})
                       db/jdbc-opts)))))))

(deftest clear-if-task-works-on-a-done-task-test
  (testing "a marker already pointing at a task stays removable after it is done"
    (let [task (db.task/add-task *ds* *user-id* "Finish up")]
      (db.working-on/set-working-on! *ds* *user-id* (:id task))
      (stamp-task-done! (:id task))
      (is (= {:task-id (:id task) :set-on (clock/today-str)}
             (db.working-on/get-working-on *ds* *user-id*)))
      (db.working-on/clear-if-task! *ds* *user-id* (:id task))
      (is (nil? (db.working-on/get-working-on *ds* *user-id*))))))

(deftest stale-marker-is-not-reported-test
  (testing "a row stamped with an earlier day has expired"
    (let [task (db.task/add-task *ds* *user-id* "Yesterday's business")]
      (db.working-on/set-working-on! *ds* *user-id* (:id task))
      (stamp-set-on! *user-id* "2020-01-01")
      (is (nil? (db.working-on/get-working-on *ds* *user-id*))))))

(deftest stale-marker-is-overwritten-by-the-next-set-test
  (testing "an expired row does not block setting the marker again"
    (let [task1 (db.task/add-task *ds* *user-id* "Yesterday's business")
          task2 (db.task/add-task *ds* *user-id* "Today's business")]
      (db.working-on/set-working-on! *ds* *user-id* (:id task1))
      (stamp-set-on! *user-id* "2020-01-01")
      (is (= {:task-id (:id task2) :set-on (clock/today-str)}
             (db.working-on/set-working-on! *ds* *user-id* (:id task2)))))))

(deftest clear-if-task-only-clears-that-task-test
  (testing "clearing names the task, so a stale unset cannot drop a newer marker"
    (let [task1 (db.task/add-task *ds* *user-id* "First")
          task2 (db.task/add-task *ds* *user-id* "Second")]
      (db.working-on/set-working-on! *ds* *user-id* (:id task2))
      (db.working-on/clear-if-task! *ds* *user-id* (:id task1))
      (is (= (:id task2) (:task-id (db.working-on/get-working-on *ds* *user-id*))))
      (db.working-on/clear-if-task! *ds* *user-id* (:id task2))
      (is (nil? (db.working-on/get-working-on *ds* *user-id*))))))

(deftest marking-the-task-done-clears-it-test
  (testing "the terminal state of a task drops the marker"
    (let [task (db.task/add-task *ds* *user-id* "Finish up")]
      (db.working-on/set-working-on! *ds* *user-id* (:id task))
      (db.task/set-task-done *ds* *user-id* (:id task) true)
      (is (nil? (db.working-on/get-working-on *ds* *user-id*))))))

(deftest marking-another-task-done-keeps-it-test
  (testing "completing an unrelated task leaves the marker alone"
    (let [task1 (db.task/add-task *ds* *user-id* "Working on this")
          task2 (db.task/add-task *ds* *user-id* "Unrelated")]
      (db.working-on/set-working-on! *ds* *user-id* (:id task1))
      (db.task/set-task-done *ds* *user-id* (:id task2) true)
      (is (= (:id task1) (:task-id (db.working-on/get-working-on *ds* *user-id*)))))))

(deftest un-doing-does-not-restore-it-test
  (testing "the marker stays gone after the task is set undone again"
    (let [task (db.task/add-task *ds* *user-id* "Finish up")]
      (db.working-on/set-working-on! *ds* *user-id* (:id task))
      (db.task/set-task-done *ds* *user-id* (:id task) true)
      (db.task/set-task-done *ds* *user-id* (:id task) false)
      (is (nil? (db.working-on/get-working-on *ds* *user-id*))))))

(deftest deleting-the-task-clears-it-test
  (testing "deleting the task drops the marker with it"
    (let [task (db.task/add-task *ds* *user-id* "Never mind")]
      (db.working-on/set-working-on! *ds* *user-id* (:id task))
      (db.task/delete-task *ds* *user-id* (:id task))
      (is (nil? (db.working-on/get-working-on *ds* *user-id*))))))

(deftest deleting-the-user-clears-it-test
  (testing "no working_on row outlives its user"
    (let [other (db.user/create-user *ds* "other-user" "testpass")
          their-task (db.task/add-task *ds* (:id other) "Their business")]
      (db.working-on/set-working-on! *ds* (:id other) (:id their-task))
      (db.user/delete-user *ds* (:id other))
      (is (nil? (db.working-on/get-working-on *ds* (:id other))))
      (is (= 0 (:cnt (jdbc/execute-one! (db/get-conn *ds*)
                       (sql/format {:select [[[:count :*] :cnt]] :from [:working_on]})
                       db/jdbc-opts)))))))
