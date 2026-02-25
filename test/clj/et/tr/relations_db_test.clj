(ns et.tr.relations-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db :as db]
            [et.tr.test-helpers :refer [*ds* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(deftest add-relation-creates-bidirectional-entries-test
  (testing "adding a relation creates entries in both directions"
    (let [task1 (db/add-task *ds* nil "Task 1")
          task2 (db/add-task *ds* nil "Task 2")]
      (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))
      (let [rels1 (db/get-relations-for-item *ds* nil "tsk" (:id task1))
            rels2 (db/get-relations-for-item *ds* nil "tsk" (:id task2))]
        (is (= 1 (count rels1)))
        (is (= 1 (count rels2)))
        (is (= {:target_type "tsk" :target_id (:id task2)}
               (select-keys (first rels1) [:target_type :target_id])))
        (is (= {:target_type "tsk" :target_id (:id task1)}
               (select-keys (first rels2) [:target_type :target_id])))))))

(deftest add-relation-cross-type-test
  (testing "can create relations between different entity types"
    (let [task (db/add-task *ds* nil "My task")
          resource (db/add-resource *ds* nil "Resource" "http://example.com" nil)]
      (db/add-relation *ds* nil "tsk" (:id task) "res" (:id resource))
      (let [task-rels (db/get-relations-for-item *ds* nil "tsk" (:id task))
            res-rels (db/get-relations-for-item *ds* nil "res" (:id resource))]
        (is (= 1 (count task-rels)))
        (is (= "res" (:target_type (first task-rels))))
        (is (= 1 (count res-rels)))
        (is (= "tsk" (:target_type (first res-rels))))))))

(deftest delete-relation-removes-both-directions-test
  (testing "deleting a relation removes both directional entries"
    (let [task1 (db/add-task *ds* nil "Task 1")
          task2 (db/add-task *ds* nil "Task 2")]
      (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))
      (db/delete-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))
      (is (empty? (db/get-relations-for-item *ds* nil "tsk" (:id task1))))
      (is (empty? (db/get-relations-for-item *ds* nil "tsk" (:id task2)))))))

(deftest get-relations-with-titles-test
  (testing "returns relations with resolved titles"
    (let [task1 (db/add-task *ds* nil "Source Task")
          task2 (db/add-task *ds* nil "Target Task")
          resource (db/add-resource *ds* nil "A Resource" "http://example.com" nil)]
      (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))
      (db/add-relation *ds* nil "tsk" (:id task1) "res" (:id resource))
      (let [rels (db/get-relations-with-titles *ds* nil "tsk" (:id task1))]
        (is (= 2 (count rels)))
        (is (some #(= "Target Task" (:title %)) rels))
        (is (some #(= "A Resource" (:title %)) rels))))))

(deftest delete-task-cascades-relations-test
  (testing "deleting a task removes its relations"
    (let [task1 (db/add-task *ds* nil "Task 1")
          task2 (db/add-task *ds* nil "Task 2")]
      (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))
      (db/delete-task *ds* nil (:id task1))
      (is (empty? (db/get-relations-for-item *ds* nil "tsk" (:id task2)))))))

(deftest delete-resource-cascades-relations-test
  (testing "deleting a resource removes its relations"
    (let [task (db/add-task *ds* nil "Task")
          resource (db/add-resource *ds* nil "Resource" "http://example.com" nil)]
      (db/add-relation *ds* nil "tsk" (:id task) "res" (:id resource))
      (db/delete-resource *ds* nil (:id resource))
      (is (empty? (db/get-relations-for-item *ds* nil "tsk" (:id task)))))))

(deftest delete-meet-cascades-relations-test
  (testing "deleting a meet removes its relations"
    (let [task (db/add-task *ds* nil "Task")
          meet (db/add-meet *ds* nil "Meet")]
      (db/add-relation *ds* nil "tsk" (:id task) "met" (:id meet))
      (db/delete-meet *ds* nil (:id meet))
      (is (empty? (db/get-relations-for-item *ds* nil "tsk" (:id task)))))))

(deftest list-tasks-includes-relations-test
  (testing "listed tasks include their relations"
    (let [task1 (db/add-task *ds* nil "Task 1")
          task2 (db/add-task *ds* nil "Task 2")]
      (db/add-relation *ds* nil "tsk" (:id task1) "tsk" (:id task2))
      (let [tasks (db/list-tasks *ds* nil {})]
        (is (some #(and (= "Task 1" (:title %))
                        (= 1 (count (:relations %))))
                  tasks))))))

(deftest list-resources-includes-relations-test
  (testing "listed resources include their relations"
    (let [resource (db/add-resource *ds* nil "Resource" "http://example.com" nil)
          task (db/add-task *ds* nil "Task")]
      (db/add-relation *ds* nil "res" (:id resource) "tsk" (:id task))
      (let [resources (db/list-resources *ds* nil {})]
        (is (some #(and (= "Resource" (:title %))
                        (= 1 (count (:relations %))))
                  resources))))))

(deftest list-meets-includes-relations-test
  (testing "listed meets include their relations"
    (let [meet (db/add-meet *ds* nil "Meet")
          task (db/add-task *ds* nil "Task")]
      (db/set-meet-start-date *ds* nil (:id meet) "2099-01-01")
      (db/add-relation *ds* nil "met" (:id meet) "tsk" (:id task))
      (let [meets (db/list-meets *ds* nil {:sort-mode :upcoming})]
        (is (some #(and (= "Meet" (:title %))
                        (= 1 (count (:relations %))))
                  meets))))))
