(ns et.tr.messages-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db :as db]
            [et.tr.test-helpers :refer [*ds* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(deftest add-message-test
  (testing "adds message with required fields"
    (let [message (db/add-message *ds* nil "John" "Hello" "Test body" nil)]
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
    (let [m1 (db/add-message *ds* nil "A" "Msg1" "" nil)
          _m2 (db/add-message *ds* nil "B" "Msg2" "" nil)]
      (db/set-message-done *ds* nil (:id m1) true)
      (let [messages (db/list-messages *ds* nil {:sort-mode :recent})]
        (is (= 1 (count messages)))
        (is (= "Msg2" (:title (first messages))))))))

(deftest list-messages-done-mode-test
  (testing "done mode returns archived messages"
    (let [m1 (db/add-message *ds* nil "A" "Msg1" "" nil)
          _m2 (db/add-message *ds* nil "B" "Msg2" "" nil)]
      (db/set-message-done *ds* nil (:id m1) true)
      (let [messages (db/list-messages *ds* nil {:sort-mode :done})]
        (is (= 1 (count messages)))
        (is (= "Msg1" (:title (first messages))))))))

(deftest set-message-done-test
  (testing "marks message as done"
    (let [message (db/add-message *ds* nil "X" "Test" "" nil)
          result (db/set-message-done *ds* nil (:id message) true)]
      (is (= 1 (:done result)))))

  (testing "can unmark message as done"
    (let [message (db/add-message *ds* nil "Y" "Test2" "" nil)
          _ (db/set-message-done *ds* nil (:id message) true)
          result (db/set-message-done *ds* nil (:id message) false)]
      (is (= 0 (:done result))))))

(deftest delete-message-test
  (testing "deletes message and returns success"
    (let [message (db/add-message *ds* nil "Z" "ToDelete" "" nil)
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
      (db/add-message *ds* nil "AdminSender" "Admin msg" "" nil)
      (db/add-message *ds* user2-id "User2Sender" "User2 msg" "" nil)
      (is (= 1 (count (db/list-messages *ds* nil))))
      (is (= 1 (count (db/list-messages *ds* user2-id))))
      (is (= "Admin msg" (:title (first (db/list-messages *ds* nil)))))
      (is (= "User2 msg" (:title (first (db/list-messages *ds* user2-id))))))))

(deftest delete-user-cleans-up-messages-test
  (testing "deleting user removes their messages"
    (let [user (db/create-user *ds* "testuser" "pass")
          user-id (:id user)]
      (db/add-message *ds* user-id "Sender" "User msg" "" nil)
      (is (= 1 (count (db/list-messages *ds* user-id))))
      (db/delete-user *ds* user-id)
      (is (= 0 (count (db/list-messages *ds* user-id)))))))

(deftest list-messages-sender-filter-test
  (testing "filters messages by sender"
    (db/add-message *ds* nil "Alice" "From Alice" "" nil)
    (db/add-message *ds* nil "Bob" "From Bob" "" nil)
    (db/add-message *ds* nil "Alice" "Also from Alice" "" nil)
    (let [filtered (db/list-messages *ds* nil {:sender-filter "Alice"})]
      (is (= 2 (count filtered)))
      (is (every? #(= "Alice" (:sender %)) filtered)))))

(deftest list-messages-excluded-senders-test
  (testing "excludes messages by sender"
    (db/add-message *ds* nil "Alice" "From Alice" "" nil)
    (db/add-message *ds* nil "Bob" "From Bob" "" nil)
    (db/add-message *ds* nil "Charlie" "From Charlie" "" nil)
    (let [filtered (db/list-messages *ds* nil {:excluded-senders #{"Alice"}})]
      (is (= 2 (count filtered)))
      (is (not-any? #(= "Alice" (:sender %)) filtered))))

  (testing "excludes multiple senders"
    (let [filtered (db/list-messages *ds* nil {:excluded-senders #{"Alice" "Bob"}})]
      (is (= 1 (count filtered)))
      (is (= "Charlie" (:sender (first filtered)))))))
