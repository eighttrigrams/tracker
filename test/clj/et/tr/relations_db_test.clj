(ns et.tr.relations-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db :as db]
            [et.tr.test-helpers :refer [*ds* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(deftest add-relation-creates-bidirectional-test
  (testing "adding a relation creates entries in both directions"
    (let [task1 (db/add-task *ds* nil "Task 1")
          task2 (db/add-task *ds* nil "Task 2")
          result (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))]
      (is (:success result))
      (let [relations1 (db/get-relations-for-item *ds* nil "tsk" (:id task1))
            relations2 (db/get-relations-for-item *ds* nil "tsk" (:id task2))]
        (is (= 1 (count relations1)))
        (is (= 1 (count relations2)))
        (is (= (:id task2) (:target_id (first relations1))))
        (is (= (:id task1) (:target_id (first relations2))))))))

(deftest add-relation-between-different-types-test
  (testing "can relate a task to a resource"
    (let [task (db/add-task *ds* nil "My task")
          resource (db/add-resource *ds* nil "My resource" "http://example.com" "both")]
      (db/add-relation *ds* nil "tsk" (:id task) "rsc" (:id resource))
      (let [task-relations (db/get-relations-for-item *ds* nil "tsk" (:id task))
            resource-relations (db/get-relations-for-item *ds* nil "rsc" (:id resource))]
        (is (= 1 (count task-relations)))
        (is (= "rsc" (:target_type (first task-relations))))
        (is (= (:id resource) (:target_id (first task-relations))))
        (is (= 1 (count resource-relations)))
        (is (= "tsk" (:target_type (first resource-relations))))
        (is (= (:id task) (:target_id (first resource-relations))))))))

(deftest delete-relation-removes-both-directions-test
  (testing "deleting a relation removes both directions"
    (let [task1 (db/add-task *ds* nil "Task 1")
          task2 (db/add-task *ds* nil "Task 2")]
      (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))
      (db/delete-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))
      (is (empty? (db/get-relations-for-item *ds* nil "tsk" (:id task1))))
      (is (empty? (db/get-relations-for-item *ds* nil "tsk" (:id task2)))))))

(deftest delete-task-removes-relations-test
  (testing "deleting a task removes its relations"
    (let [task1 (db/add-task *ds* nil "Task 1")
          task2 (db/add-task *ds* nil "Task 2")]
      (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))
      (db/delete-task *ds* nil (:id task1))
      (is (empty? (db/get-relations-for-item *ds* nil "tsk" (:id task2)))))))

(deftest delete-resource-removes-relations-test
  (testing "deleting a resource removes its relations"
    (let [task (db/add-task *ds* nil "My task")
          resource (db/add-resource *ds* nil "My resource" "http://example.com" "both")]
      (db/add-relation *ds* nil "tsk" (:id task) "rsc" (:id resource))
      (db/delete-resource *ds* nil (:id resource))
      (is (empty? (db/get-relations-for-item *ds* nil "tsk" (:id task)))))))

(deftest delete-meet-removes-relations-test
  (testing "deleting a meet removes its relations"
    (let [task (db/add-task *ds* nil "My task")
          meet (db/add-meet *ds* nil "My meet")]
      (db/add-relation *ds* nil "tsk" (:id task) "met" (:id meet))
      (db/delete-meet *ds* nil (:id meet))
      (is (empty? (db/get-relations-for-item *ds* nil "tsk" (:id task)))))))

(deftest list-tasks-includes-relations-test
  (testing "list-tasks includes relations with titles"
    (let [task1 (db/add-task *ds* nil "Task 1")
          task2 (db/add-task *ds* nil "Task 2")]
      (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))
      (let [tasks (db/list-tasks *ds* nil)]
        (is (seq tasks))
        (let [t1 (first (filter #(= (:id %) (:id task1)) tasks))
              t2 (first (filter #(= (:id %) (:id task2)) tasks))]
          (is (= 1 (count (:relations t1))))
          (is (= "Task 2" (:title (first (:relations t1)))))
          (is (= 1 (count (:relations t2))))
          (is (= "Task 1" (:title (first (:relations t2))))))))))

(deftest list-resources-includes-relations-test
  (testing "list-resources includes relations with titles"
    (let [resource (db/add-resource *ds* nil "My resource" "http://example.com" "both")
          task (db/add-task *ds* nil "My task")]
      (db/add-relation *ds* nil "rsc" (:id resource) "tsk" (:id task))
      (let [resources (db/list-resources *ds* nil)]
        (is (= 1 (count resources)))
        (is (= 1 (count (:relations (first resources)))))
        (is (= "My task" (:title (first (:relations (first resources))))))))))

(deftest list-meets-includes-relations-test
  (testing "list-meets includes relations with titles"
    (let [meet (db/add-meet *ds* nil "My meet")
          task (db/add-task *ds* nil "My task")]
      (db/set-meet-start-date *ds* nil (:id meet) "2099-01-01")
      (db/add-relation *ds* nil "met" (:id meet) "tsk" (:id task))
      (let [meets (db/list-meets *ds* nil {:sort-mode :upcoming})]
        (is (= 1 (count meets)))
        (is (= 1 (count (:relations (first meets)))))
        (is (= "My task" (:title (first (:relations (first meets))))))))))
