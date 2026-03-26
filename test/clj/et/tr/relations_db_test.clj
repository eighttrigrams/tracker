(ns et.tr.relations-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db.meet :as db.meet]
            [et.tr.db.relation :as db.relation]
            [et.tr.db.resource :as db.resource]
            [et.tr.db.task :as db.task]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(deftest add-relation-creates-bidirectional-entries-test
  (testing "adding a relation creates entries in both directions"
    (let [task1 (db.task/add-task *ds* *user-id* "Task 1")
          task2 (db.task/add-task *ds* *user-id* "Task 2")]
      (db.relation/add-relation *ds* *user-id* "tsk" (:id task1) "tsk" (:id task2))
      (let [rels1 (db.relation/get-relations-for-item *ds* *user-id* "tsk" (:id task1))
            rels2 (db.relation/get-relations-for-item *ds* *user-id* "tsk" (:id task2))]
        (is (= 1 (count rels1)))
        (is (= 1 (count rels2)))
        (is (= {:target_type "tsk" :target_id (:id task2)}
               (select-keys (first rels1) [:target_type :target_id])))
        (is (= {:target_type "tsk" :target_id (:id task1)}
               (select-keys (first rels2) [:target_type :target_id])))))))

(deftest add-relation-cross-type-test
  (testing "can create relations between different entity types"
    (let [task (db.task/add-task *ds* *user-id* "My task")
          resource (db.resource/add-resource *ds* *user-id* "Resource" "http://example.com" nil)]
      (db.relation/add-relation *ds* *user-id* "tsk" (:id task) "res" (:id resource))
      (let [task-rels (db.relation/get-relations-for-item *ds* *user-id* "tsk" (:id task))
            res-rels (db.relation/get-relations-for-item *ds* *user-id* "res" (:id resource))]
        (is (= 1 (count task-rels)))
        (is (= "res" (:target_type (first task-rels))))
        (is (= 1 (count res-rels)))
        (is (= "tsk" (:target_type (first res-rels))))))))

(deftest delete-relation-removes-both-directions-test
  (testing "deleting a relation removes both directional entries"
    (let [task1 (db.task/add-task *ds* *user-id* "Task 1")
          task2 (db.task/add-task *ds* *user-id* "Task 2")]
      (db.relation/add-relation *ds* *user-id* "tsk" (:id task1) "tsk" (:id task2))
      (db.relation/delete-relation *ds* *user-id* "tsk" (:id task1) "tsk" (:id task2))
      (is (empty? (db.relation/get-relations-for-item *ds* *user-id* "tsk" (:id task1))))
      (is (empty? (db.relation/get-relations-for-item *ds* *user-id* "tsk" (:id task2)))))))

(deftest get-relations-with-titles-test
  (testing "returns relations with resolved titles"
    (let [task1 (db.task/add-task *ds* *user-id* "Source Task")
          task2 (db.task/add-task *ds* *user-id* "Target Task")
          resource (db.resource/add-resource *ds* *user-id* "A Resource" "http://example.com" nil)]
      (db.relation/add-relation *ds* *user-id* "tsk" (:id task1) "tsk" (:id task2))
      (db.relation/add-relation *ds* *user-id* "tsk" (:id task1) "res" (:id resource))
      (let [rels (db.relation/get-relations-with-titles *ds* *user-id* "tsk" (:id task1))]
        (is (= 2 (count rels)))
        (is (some #(= "Target Task" (:title %)) rels))
        (is (some #(= "A Resource" (:title %)) rels))))))

(deftest delete-task-cascades-relations-test
  (testing "deleting a task removes its relations"
    (let [task1 (db.task/add-task *ds* *user-id* "Task 1")
          task2 (db.task/add-task *ds* *user-id* "Task 2")]
      (db.relation/add-relation *ds* *user-id* "tsk" (:id task1) "tsk" (:id task2))
      (db.task/delete-task *ds* *user-id* (:id task1))
      (is (empty? (db.relation/get-relations-for-item *ds* *user-id* "tsk" (:id task2)))))))

(deftest delete-resource-cascades-relations-test
  (testing "deleting a resource removes its relations"
    (let [task (db.task/add-task *ds* *user-id* "Task")
          resource (db.resource/add-resource *ds* *user-id* "Resource" "http://example.com" nil)]
      (db.relation/add-relation *ds* *user-id* "tsk" (:id task) "res" (:id resource))
      (db.resource/delete-resource *ds* *user-id* (:id resource))
      (is (empty? (db.relation/get-relations-for-item *ds* *user-id* "tsk" (:id task)))))))

(deftest delete-meet-cascades-relations-test
  (testing "deleting a meet removes its relations"
    (let [task (db.task/add-task *ds* *user-id* "Task")
          meet (db.meet/add-meet *ds* *user-id* "Meet")]
      (db.relation/add-relation *ds* *user-id* "tsk" (:id task) "met" (:id meet))
      (db.meet/delete-meet *ds* *user-id* (:id meet))
      (is (empty? (db.relation/get-relations-for-item *ds* *user-id* "tsk" (:id task)))))))

(deftest list-tasks-includes-relations-test
  (testing "listed tasks include their relations"
    (let [task1 (db.task/add-task *ds* *user-id* "Task 1")
          task2 (db.task/add-task *ds* *user-id* "Task 2")]
      (db.relation/add-relation *ds* *user-id* "tsk" (:id task1) "tsk" (:id task2))
      (let [tasks (db.task/list-tasks *ds* *user-id* {})]
        (is (some #(and (= "Task 1" (:title %))
                        (= 1 (count (:relations %))))
                  tasks))))))

(deftest list-resources-includes-relations-test
  (testing "listed resources include their relations"
    (let [resource (db.resource/add-resource *ds* *user-id* "Resource" "http://example.com" nil)
          task (db.task/add-task *ds* *user-id* "Task")]
      (db.relation/add-relation *ds* *user-id* "res" (:id resource) "tsk" (:id task))
      (let [resources (db.resource/list-resources *ds* *user-id* {})]
        (is (some #(and (= "Resource" (:title %))
                        (= 1 (count (:relations %))))
                  resources))))))

(deftest list-meets-includes-relations-test
  (testing "listed meets include their relations"
    (let [meet (db.meet/add-meet *ds* *user-id* "Meet")
          task (db.task/add-task *ds* *user-id* "Task")]
      (db.meet/set-meet-start-date *ds* *user-id* (:id meet) "2099-01-01")
      (db.relation/add-relation *ds* *user-id* "met" (:id meet) "tsk" (:id task))
      (let [meets (db.meet/list-meets *ds* *user-id* {:sort-mode :upcoming})]
        (is (some #(and (= "Meet" (:title %))
                        (= 1 (count (:relations %))))
                  meets))))))
