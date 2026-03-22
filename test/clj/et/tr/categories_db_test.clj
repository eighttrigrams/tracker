(ns et.tr.categories-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db.category :as db.category]
            [et.tr.db.user :as db.user]
            [et.tr.db.task :as db.task]
            [et.tr.db.resource :as db.resource]
            [et.tr.db.meet :as db.meet]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [et.tr.test-helpers :refer [*ds* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(deftest people-crud-test
  (testing "add and list people"
    (db.category/add-person *ds* nil "Alice")
    (db.category/add-person *ds* nil "Bob")
    (let [people (db.category/list-people *ds* nil)]
      (is (= 2 (count people)))
      (is (= ["Alice" "Bob"] (map :name people))))))

(deftest places-crud-test
  (testing "add and list places"
    (db.category/add-place *ds* nil "Home")
    (db.category/add-place *ds* nil "Work")
    (let [places (db.category/list-places *ds* nil)]
      (is (= 2 (count places)))
      (is (= ["Home" "Work"] (map :name places))))))

(deftest projects-crud-test
  (testing "add and list projects"
    (db.category/add-project *ds* nil "Alpha")
    (db.category/add-project *ds* nil "Beta")
    (let [projects (db.category/list-projects *ds* nil)]
      (is (= 2 (count projects)))
      (is (= ["Alpha" "Beta"] (map :name projects))))))

(deftest goals-crud-test
  (testing "add and list goals"
    (db.category/add-goal *ds* nil "Learn Clojure")
    (db.category/add-goal *ds* nil "Ship product")
    (let [goals (db.category/list-goals *ds* nil)]
      (is (= 2 (count goals)))
      (is (= ["Learn Clojure" "Ship product"] (map :name goals))))))

(deftest category-name-unique-per-user-test
  (testing "different users can have categories with the same name"
    (let [user1 (db.user/create-user *ds* "user1" "pass1")
          user2 (db.user/create-user *ds* "user2" "pass2")
          user1-id (:id user1)
          user2-id (:id user2)]
      (db.category/add-person *ds* user1-id "John")
      (db.category/add-person *ds* user2-id "John")
      (db.category/add-place *ds* user1-id "Office")
      (db.category/add-place *ds* user2-id "Office")
      (db.category/add-project *ds* user1-id "Alpha")
      (db.category/add-project *ds* user2-id "Alpha")
      (db.category/add-goal *ds* user1-id "Launch")
      (db.category/add-goal *ds* user2-id "Launch")
      (is (= ["John"] (map :name (db.category/list-people *ds* user1-id))))
      (is (= ["John"] (map :name (db.category/list-people *ds* user2-id))))
      (is (= ["Office"] (map :name (db.category/list-places *ds* user1-id))))
      (is (= ["Office"] (map :name (db.category/list-places *ds* user2-id))))
      (is (= ["Alpha"] (map :name (db.category/list-projects *ds* user1-id))))
      (is (= ["Alpha"] (map :name (db.category/list-projects *ds* user2-id))))
      (is (= ["Launch"] (map :name (db.category/list-goals *ds* user1-id))))
      (is (= ["Launch"] (map :name (db.category/list-goals *ds* user2-id))))))

  (testing "same user cannot have duplicate category names"
    (let [user (db.user/create-user *ds* "testuser" "pass")
          user-id (:id user)]
      (db.category/add-person *ds* user-id "Alice")
      (is (thrown? Exception (db.category/add-person *ds* user-id "Alice"))))))

(defn- count-join-rows [table]
  (:cnt (jdbc/execute-one! (:conn *ds*)
          (sql/format {:select [[[:count :*] :cnt]] :from [table]})
          {:builder-fn rs/as-unqualified-maps})))

(deftest delete-category-cleans-up-task-categories-test
  (let [task (db.task/add-task *ds* nil "My task")
        person (db.category/add-person *ds* nil "Alice")]
    (db.task/categorize-task *ds* nil (:id task) "person" (:id person))
    (is (= 1 (count-join-rows :task_categories)))
    (db.category/delete-category *ds* nil (:id person) "person" "people")
    (is (= 0 (count-join-rows :task_categories)))))

(deftest delete-category-cleans-up-resource-categories-test
  (let [resource (db.resource/add-resource *ds* nil "My resource" "https://example.com" "both")
        person (db.category/add-person *ds* nil "Alice")]
    (db.resource/categorize-resource *ds* nil (:id resource) "person" (:id person))
    (is (= 1 (count-join-rows :resource_categories)))
    (db.category/delete-category *ds* nil (:id person) "person" "people")
    (is (= 0 (count-join-rows :resource_categories)))))

(deftest delete-category-cleans-up-meet-categories-test
  (let [meet (db.meet/add-meet *ds* nil "My meet")
        person (db.category/add-person *ds* nil "Alice")]
    (db.meet/categorize-meet *ds* nil (:id meet) "person" (:id person))
    (is (= 1 (count-join-rows :meet_categories)))
    (db.category/delete-category *ds* nil (:id person) "person" "people")
    (is (= 0 (count-join-rows :meet_categories)))))

(deftest update-category-badge-title-test
  (let [person (db.category/add-person *ds* nil "Alice Johnson")]
    (is (= "" (:badge_title person)))
    (let [updated (db.category/update-person *ds* nil (:id person) "Alice Johnson" "" "" "AJ")]
      (is (= "AJ" (:badge_title updated))))
    (let [listed (first (db.category/list-people *ds* nil))]
      (is (= "AJ" (:badge_title listed))))))

(deftest badge-title-appears-on-tasks-test
  (let [person (db.category/add-person *ds* nil "Alice Johnson")
        _ (db.category/update-person *ds* nil (:id person) "Alice Johnson" "" "" "AJ")
        task (db.task/add-task *ds* nil "My task")]
    (db.task/categorize-task *ds* nil (:id task) "person" (:id person))
    (let [tasks (db.task/list-tasks *ds* nil)
          t (first tasks)
          p (first (:people t))]
      (is (= "AJ" (:badge_title p))))))
