(ns et.tr.categories-db-test
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
