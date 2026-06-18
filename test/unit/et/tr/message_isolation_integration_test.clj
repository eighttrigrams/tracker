(ns et.tr.message-isolation-integration-test
  "Integration coverage that message-mutating endpoints are scoped to the
  owning user. A second user (the attacker) must never be able to delete,
  edit, merge, or convert another user's message — the owner's row must
  survive every cross-user attempt, and the attacker gets a 404."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [ring.mock.request :as mock]
            [et.tr.db.message :as db.message]
            [et.tr.db.task :as db.task]
            [et.tr.db.user :as db.user]
            [et.tr.integration-helpers :refer [with-integration-db *app* *ds* *user-id*]]))

(use-fixtures :each with-integration-db)

(defn- as-user
  "Issue an HTTP request to *app* authenticating as `user-id`."
  ([method path user-id] (as-user method path user-id nil))
  ([method path user-id body]
   (let [req (cond-> (-> (mock/request method path)
                         (mock/header "X-User-Id" (str user-id)))
               body (-> (mock/header "Content-Type" "application/json")
                        (mock/body (json/write-str body))))]
     (update (*app* req) :body #(when (seq %) (json/read-str % :key-fn keyword))))))

(defn- attacker-id []
  (:id (db.user/create-user *ds* "attacker" "pass")))

(defn- owned-message []
  (db.message/add-message *ds* *user-id* "owner" "owned" "orig body" nil nil nil nil))

(deftest cannot-delete-another-users-message
  (let [attacker (attacker-id)
        msg (owned-message)
        resp (as-user :delete (str "/api/messages/" (:id msg)) attacker)]
    (is (= 404 (:status resp)))
    (is (some? (db.message/get-message *ds* *user-id* (:id msg)))
        "owner's message survives the cross-user delete")))

(deftest cannot-update-another-users-message
  (let [attacker (attacker-id)
        msg (owned-message)
        resp (as-user :put (str "/api/messages/" (:id msg)) attacker
                      {:title "hacked" :description "tampered"})]
    (is (= 404 (:status resp)))
    (let [reloaded (db.message/get-message *ds* *user-id* (:id msg))]
      (is (= "owned" (:title reloaded)) "title unchanged")
      (is (= "orig body" (:description reloaded)) "description unchanged"))))

(deftest cannot-convert-another-users-message-to-task
  (let [attacker (attacker-id)
        msg (owned-message)
        resp (as-user :post (str "/api/messages/" (:id msg) "/convert-to-task") attacker {})]
    (is (= 404 (:status resp)))
    (is (some? (db.message/get-message *ds* *user-id* (:id msg)))
        "owner's message survives")
    (is (empty? (db.task/list-tasks *ds* attacker :recent nil))
        "no task created for the attacker")
    (is (empty? (db.task/list-tasks *ds* *user-id* :recent nil))
        "no task created for the owner")))

(deftest cannot-convert-another-users-message-to-resource
  (let [attacker (attacker-id)
        msg (owned-message)
        resp (as-user :post (str "/api/messages/" (:id msg) "/convert-to-resource") attacker
                      {:link "https://example.com"})]
    (is (= 404 (:status resp)))
    (is (some? (db.message/get-message *ds* *user-id* (:id msg)))
        "owner's message survives")))

(deftest cannot-merge-away-another-users-source-message
  (testing "attacker merges the owner's message (as source) into their own target"
    (let [attacker (attacker-id)
          victim (owned-message)
          target (db.message/add-message *ds* attacker "attacker" "target" "" nil nil nil nil)
          resp (as-user :post (str "/api/messages/" (:id victim) "/merge") attacker
                        {:target-id (:id target)})]
      (is (= 404 (:status resp)))
      (is (some? (db.message/get-message *ds* *user-id* (:id victim)))
          "owner's message is not deleted as a merge source")
      (is (= "target" (:title (db.message/get-message *ds* attacker (:id target))))
          "attacker's own target is untouched"))))

(deftest cannot-merge-into-another-users-target-message
  (testing "attacker merges their own message into the owner's message (as target)"
    (let [attacker (attacker-id)
          victim (owned-message)
          source (db.message/add-message *ds* attacker "attacker" "source" "" nil nil nil nil)
          resp (as-user :post (str "/api/messages/" (:id source) "/merge") attacker
                        {:target-id (:id victim)})]
      (is (= 404 (:status resp)))
      (is (= "owned" (:title (db.message/get-message *ds* *user-id* (:id victim))))
          "owner's message title is not modified by the merge")
      (is (some? (db.message/get-message *ds* attacker (:id source)))
          "attacker's source still exists (merge did not run)"))))

(deftest owner-can-still-delete-own-message
  (testing "the inline user_id scoping does not break the legitimate path"
    (let [msg (owned-message)
          resp (as-user :delete (str "/api/messages/" (:id msg)) *user-id*)]
      (is (= 200 (:status resp)))
      (is (nil? (db.message/get-message *ds* *user-id* (:id msg)))))))
