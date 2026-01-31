(ns et.tr.messages-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db :as db]
            [et.tr.test-helpers :refer [*ds* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

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
