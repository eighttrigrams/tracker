(ns et.tr.users-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db :as db]
            [et.tr.test-helpers :refer [*ds* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

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
          person (db/add-person *ds* user-id "User person")]
      (db/add-place *ds* user-id "User place")
      (db/add-project *ds* user-id "User project")
      (db/add-goal *ds* user-id "User goal")
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
