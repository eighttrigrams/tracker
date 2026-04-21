(ns et.tr.rest-api-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [ring.mock.request :as mock]
            [et.tr.auth :as auth]
            [et.tr.db.user :as db.user]
            [et.tr.db.task :as db.task]
            [et.tr.server.common :as common]
            [et.tr.integration-helpers :refer [with-integration-db *app* *ds* *user-id*]]
            [et.tr.server.rest-api.middleware :as mw]))

(use-fixtures :each with-integration-db)

(defn- token-for [user-id username]
  (auth/create-token user-id username false false))

(defn- REST-json
  [method path {:keys [body token user-id]
                :or {user-id *user-id*}}]
  (let [req (cond-> (mock/request method path)
              token   (mock/header "Authorization" (str "Bearer " token))
              user-id (mock/header "X-User-Id" (str user-id))
              body    (-> (mock/header "Content-Type" "application/json")
                          (mock/body (json/write-str body))))]
    (update (*app* req) :body #(when (seq %) (json/read-str % :key-fn keyword)))))

(defn- ensure-recording-off! []
  (when (mw/enabled?) (mw/toggle!)))

(defn- ensure-recording-on! []
  (when-not (mw/enabled?) (mw/toggle!)))

(deftest describe-lists-all-handlers-with-docstrings
  (let [resp (REST-json :get "/rest/describe" {})
        names (set (map :name (:body resp)))]
    (is (= 200 (:status resp)))
    (is (contains? names "list-tasks"))
    (is (contains? names "list-today"))
    (is (contains? names "create-task"))
    (is (every? (fn [h] (seq (:doc h))) (:body resp)))))

(deftest dev-mode-allows-unauthenticated-reads
  (testing "X-User-Id header selects the user"
    (is (= 200 (:status (REST-json :get "/rest/tasks" {:user-id *user-id*})))))
  (testing "no auth headers at all — defaults to first user"
    (is (= 200 (:status (REST-json :get "/rest/tasks" {:user-id nil}))))))

(deftest prod-mode-requires-token
  (with-redefs [common/prod-mode? (constantly true)]
    (testing "no token"
      (is (= 401 (:status (REST-json :get "/rest/tasks" {:user-id nil})))))
    (testing "invalid token"
      (is (= 401 (:status (REST-json :get "/rest/tasks"
                                      {:user-id nil :token "garbage"})))))))

(deftest rest-auth-does-not-gate-non-rest-routes-in-prod
  (testing "GET / (health check) must not hit the REST auth middleware"
    (with-redefs [common/prod-mode? (constantly true)]
      (let [resp (*app* (mock/request :get "/"))]
        (is (not= 401 (:status resp))
            "GET / returned 401 — wrap-rest-auth is firing on non-/rest routes"))))
  (testing "GET /api/auth/required must pass through"
    (with-redefs [common/prod-mode? (constantly true)]
      (let [resp (*app* (mock/request :get "/api/auth/required"))]
        (is (not= 401 (:status resp)))))))

(deftest login-issues-working-token-and-authorises-in-prod
  (let [user (db.user/create-user *ds* "alice" "secret123")
        login-resp (REST-json :post "/rest/auth/login"
                              {:body {:username "alice" :password "secret123"}})
        token (:token (:body login-resp))]
    (is (= 200 (:status login-resp)))
    (is (string? token))
    (is (= (:id user) (:user-id (auth/verify-token token))))
    (with-redefs [common/prod-mode? (constantly true)]
      (is (= 200 (:status (REST-json :get "/rest/tasks"
                                      {:user-id nil :token token})))))))

(deftest login-rejects-bad-credentials
  (db.user/create-user *ds* "bob" "secret123")
  (let [resp (REST-json :post "/rest/auth/login"
                        {:body {:username "bob" :password "wrong"}})]
    (is (= 401 (:status resp)))))

(deftest list-today-returns-today-flagged-tasks
  (ensure-recording-off!)
  (let [task (db.task/add-task *ds* *user-id* "Ship it")]
    (db.task/set-task-today *ds* *user-id* (:id task) true)
    (let [resp (REST-json :get "/rest/tasks/today" {})
          titles (set (map :title (:body resp)))]
      (is (= 200 (:status resp)))
      (is (contains? titles "Ship it")))))

(deftest list-today-isolates-users-in-prod
  (ensure-recording-off!)
  (let [other (db.user/create-user *ds* "carol" "secret123")
        other-task (db.task/add-task *ds* (:id other) "Carol's task")]
    (db.task/set-task-today *ds* (:id other) (:id other-task) true)
    (with-redefs [common/prod-mode? (constantly true)]
      (let [my-token (token-for *user-id* "test-user")
            resp (REST-json :get "/rest/tasks/today"
                            {:user-id nil :token my-token})
            titles (set (map :title (:body resp)))]
        (is (= 200 (:status resp)))
        (is (not (contains? titles "Carol's task")))))))

(deftest create-task-dropped-when-recording-off
  (ensure-recording-off!)
  (let [resp (REST-json :post "/rest/tasks" {:body {:title "Dropped task"}})]
    (is (= 201 (:status resp)))
    (is (true? (:created (:body resp))))
    (let [list-resp (REST-json :get "/rest/tasks" {})
          titles (set (map :title (:body list-resp)))]
      (is (not (contains? titles "Dropped task"))))))

(deftest create-task-persists-when-recording-on
  (ensure-recording-on!)
  (try
    (let [resp (REST-json :post "/rest/tasks" {:body {:title "Real task"}})]
      (is (= 201 (:status resp)))
      (is (= "Real task" (:title (:body resp))))
      (let [list-resp (REST-json :get "/rest/tasks" {})
            titles (set (map :title (:body list-resp)))]
        (is (contains? titles "Real task"))))
    (finally (ensure-recording-off!))))

(deftest toggle-recording-mode-is-not-reachable-via-rest
  (let [resp (REST-json :post "/rest/recording-mode/toggle" {})]
    (is (= 404 (:status resp))
        "recording-mode toggle must not be exposed on /rest/*")))

(deftest toggle-recording-mode-flips-state-via-api
  (ensure-recording-off!)
  (let [r1 (REST-json :post "/api/recording-mode/toggle" {})
        r2 (REST-json :post "/api/recording-mode/toggle" {})]
    (is (true? (:recording (:body r1))))
    (is (false? (:recording (:body r2))))))
