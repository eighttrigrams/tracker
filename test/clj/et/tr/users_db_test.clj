(ns et.tr.users-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db.user :as db.user]
            [et.tr.db.task :as db.task]
            [et.tr.db.category :as db.category]
            [et.tr.test-helpers :refer [*ds* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(deftest user-data-isolation-test
  (testing "users see only their own data"
    (let [user2 (db.user/create-user *ds* "user2" "pass")
          user2-id (:id user2)]
      (db.task/add-task *ds* nil "Admin task")
      (db.task/add-task *ds* user2-id "User2 task")
      (db.category/add-person *ds* nil "Admin person")
      (db.category/add-person *ds* user2-id "User2 person")
      (is (= 1 (count (db.task/list-tasks *ds* nil))))
      (is (= 1 (count (db.task/list-tasks *ds* user2-id))))
      (is (= "Admin task" (:title (first (db.task/list-tasks *ds* nil)))))
      (is (= "User2 task" (:title (first (db.task/list-tasks *ds* user2-id)))))
      (is (= ["Admin person"] (map :name (db.category/list-people *ds* nil))))
      (is (= ["User2 person"] (map :name (db.category/list-people *ds* user2-id)))))))

(deftest delete-user-cleans-up-data-test
  (testing "deleting user removes all their data"
    (let [user (db.user/create-user *ds* "testuser" "pass")
          user-id (:id user)
          task (db.task/add-task *ds* user-id "User task")
          person (db.category/add-person *ds* user-id "User person")]
      (db.category/add-place *ds* user-id "User place")
      (db.category/add-project *ds* user-id "User project")
      (db.category/add-goal *ds* user-id "User goal")
      (db.task/categorize-task *ds* user-id (:id task) "person" (:id person))
      (is (= 1 (count (db.task/list-tasks *ds* user-id))))
      (is (= 1 (count (db.category/list-people *ds* user-id))))
      (is (= 1 (count (db.category/list-places *ds* user-id))))
      (is (= 1 (count (db.category/list-projects *ds* user-id))))
      (is (= 1 (count (db.category/list-goals *ds* user-id))))
      (db.user/delete-user *ds* user-id)
      (is (= 0 (count (db.task/list-tasks *ds* user-id))))
      (is (= 0 (count (db.category/list-people *ds* user-id))))
      (is (= 0 (count (db.category/list-places *ds* user-id))))
      (is (= 0 (count (db.category/list-projects *ds* user-id))))
      (is (= 0 (count (db.category/list-goals *ds* user-id))))
      (is (nil? (db.user/get-user-by-username *ds* "testuser"))))))
