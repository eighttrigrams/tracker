(ns et.tr.relations-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db :as db]
            [et.tr.test-helpers :refer [*ds* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(deftest add-relation-between-tasks-test
  (testing "creates relation between two tasks"
    (let [task1 (db/add-task *ds* nil "Task 1")
          task2 (db/add-task *ds* nil "Task 2")
          relation (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))]
      (is (some? (:id relation)))
      (is (= "tsk" (:source_type relation)))
      (is (= (:id task1) (:source_id relation)))
      (is (= "tsk" (:target_type relation)))
      (is (= (:id task2) (:target_id relation))))))

(deftest add-relation-between-different-types-test
  (testing "creates relation between task and resource"
    (let [task (db/add-task *ds* nil "Task 1")
          resource (db/add-resource *ds* nil "Resource 1" "http://example.com" "both")
          relation (db/add-relation *ds* nil "tsk" (:id task) "rsc" (:id resource))]
      (is (some? (:id relation)))
      (is (= "tsk" (:source_type relation)))
      (is (= "rsc" (:target_type relation))))))

(deftest add-relation-task-to-meet-test
  (testing "creates relation between task and meet"
    (let [task (db/add-task *ds* nil "Task 1")
          meet (db/add-meet *ds* nil "Meeting 1")
          relation (db/add-relation *ds* nil "tsk" (:id task) "mee" (:id meet))]
      (is (some? (:id relation)))
      (is (= "tsk" (:source_type relation)))
      (is (= "mee" (:target_type relation))))))

(deftest list-relations-for-item-test
  (testing "lists relations for a task as source"
    (let [task1 (db/add-task *ds* nil "Task 1")
          task2 (db/add-task *ds* nil "Task 2")
          task3 (db/add-task *ds* nil "Task 3")]
      (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))
      (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task3))
      (let [relations (db/list-relations-for-item *ds* nil "tsk" (:id task1))]
        (is (= 2 (count relations)))
        (is (every? #(= (:id task1) (:source_id %)) relations))))))

(deftest list-relations-bidirectional-test
  (testing "lists relations for a task as both source and target"
    (let [task1 (db/add-task *ds* nil "Task 1")
          task2 (db/add-task *ds* nil "Task 2")
          task3 (db/add-task *ds* nil "Task 3")]
      (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))
      (db/add-relation *ds* nil "tsk" (:id task3) "tsk" (:id task1))
      (let [relations (db/list-relations-for-item *ds* nil "tsk" (:id task1))]
        (is (= 2 (count relations)))))))

(deftest list-relations-includes-other-title-test
  (testing "relations include other item's title"
    (let [task1 (db/add-task *ds* nil "Task One")
          task2 (db/add-task *ds* nil "Task Two")]
      (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))
      (let [relations (db/list-relations-for-item *ds* nil "tsk" (:id task1))
            rel (first relations)]
        (is (= "Task Two" (:other_title rel)))
        (is (= "tsk" (:other_type rel)))
        (is (= (:id task2) (:other_id rel)))))))

(deftest remove-relation-test
  (testing "removes a relation"
    (let [task1 (db/add-task *ds* nil "Task 1")
          task2 (db/add-task *ds* nil "Task 2")
          relation (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))]
      (is (some? (:id relation)))
      (let [result (db/remove-relation *ds* nil (:id relation))]
        (is (:success result))
        (is (= [] (db/list-relations-for-item *ds* nil "tsk" (:id task1))))))))

(deftest delete-task-removes-relations-test
  (testing "deleting a task removes its relations"
    (let [task1 (db/add-task *ds* nil "Task 1")
          task2 (db/add-task *ds* nil "Task 2")]
      (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))
      (is (= 1 (count (db/list-relations-for-item *ds* nil "tsk" (:id task1)))))
      (db/delete-task *ds* nil (:id task1))
      (is (= [] (db/list-relations-for-item *ds* nil "tsk" (:id task2)))))))

(deftest delete-resource-removes-relations-test
  (testing "deleting a resource removes its relations"
    (let [task (db/add-task *ds* nil "Task 1")
          resource (db/add-resource *ds* nil "Resource 1" "http://example.com" "both")]
      (db/add-relation *ds* nil "tsk" (:id task) "rsc" (:id resource))
      (is (= 1 (count (db/list-relations-for-item *ds* nil "tsk" (:id task)))))
      (db/delete-resource *ds* nil (:id resource))
      (is (= [] (db/list-relations-for-item *ds* nil "tsk" (:id task)))))))

(deftest delete-meet-removes-relations-test
  (testing "deleting a meet removes its relations"
    (let [task (db/add-task *ds* nil "Task 1")
          meet (db/add-meet *ds* nil "Meeting 1")]
      (db/add-relation *ds* nil "tsk" (:id task) "mee" (:id meet))
      (is (= 1 (count (db/list-relations-for-item *ds* nil "tsk" (:id task)))))
      (db/delete-meet *ds* nil (:id meet))
      (is (= [] (db/list-relations-for-item *ds* nil "tsk" (:id task)))))))

(deftest duplicate-relation-with-user-is-ignored-test
  (testing "creating duplicate relation with same user is ignored"
    (let [user (db/create-user *ds* "testuser" "testpass")
          task1 (db/add-task *ds* (:id user) "Task 1")
          task2 (db/add-task *ds* (:id user) "Task 2")]
      (db/add-relation *ds* (:id user) "tsk" (:id task1) "tsk" (:id task2))
      (db/add-relation *ds* (:id user) "tsk" (:id task1) "tsk" (:id task2))
      (is (= 1 (count (db/list-relations-for-item *ds* (:id user) "tsk" (:id task1))))))))

(deftest invalid-relation-type-throws-test
  (testing "invalid relation type throws exception"
    (is (thrown? Exception (db/add-relation *ds* nil "invalid" 1 "tsk" 2)))))
