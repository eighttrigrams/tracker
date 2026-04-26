(ns et.tr.machine-user-integration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.data.json :as json]
            [ring.mock.request :as mock]
            [et.tr.auth :as auth]
            [et.tr.db.user :as db.user]
            [et.tr.db.task :as db.task]
            [et.tr.server.common :as common]
            [et.tr.server.recording-mode :as recording-mode]
            [et.tr.integration-helpers :refer [with-integration-db *app* *ds* *user-id*]]))

(use-fixtures :each with-integration-db)

(defn- with-real-auth* [f]
  (with-redefs [common/allow-skip-logins? (constantly false)]
    (f)))

(defmacro with-real-auth [& body]
  `(with-real-auth* (fn [] ~@body)))

(defn- machine-token-for [machine-id target-id]
  (auth/create-token {:user-id machine-id
                      :username "machine"
                      :is-admin false
                      :has-mail false
                      :is-machine-user true
                      :for-user-id target-id}))

(defn- API
  [method path {:keys [body token as-admin]}]
  (let [req (cond-> (mock/request method path)
              token                     (mock/header "Authorization" (str "Bearer " token))
              (and (not token)
                   (not as-admin))      (mock/header "X-User-Id" (str *user-id*))
              body                      (-> (mock/header "Content-Type" "application/json")
                                            (mock/body (json/write-str body))))]
    (update (*app* req) :body #(when (seq %) (json/read-str % :key-fn keyword)))))

(defn- ensure-recording-off! []
  (when (recording-mode/enabled?) (recording-mode/toggle!)))

(defn- ensure-recording-on! []
  (when-not (recording-mode/enabled?) (recording-mode/toggle!)))

(defn- create-machine-user! [target-id]
  (db.user/create-user *ds* "machine" "machinepass"
                       {:is-machine-user true :for-user-id target-id}))

(deftest machine-login-issues-token-with-machine-claims
  (with-real-auth
    (create-machine-user! *user-id*)
    (let [resp (API :post "/api/auth/login"
                    {:body {:username "machine" :password "machinepass"}})
          token (:token (:body resp))
          claims (auth/verify-token token)]
      (is (= 200 (:status resp)))
      (is (string? token))
      (is (true? (:is-machine-user claims)))
      (is (= *user-id* (:for-user-id claims))))))

(deftest machine-reads-pass-through-and-target-the-bound-user
  (with-real-auth
    (ensure-recording-off!)
    (let [machine (create-machine-user! *user-id*)
          token (machine-token-for (:id machine) *user-id*)
          task (db.task/add-task *ds* *user-id* "Owner task")]
      (db.task/set-task-today *ds* *user-id* (:id task) true)
      (let [resp (API :get "/api/tasks?sort=today" {:token token})
            titles (set (map :title (:body resp)))]
        (is (= 200 (:status resp)))
        (is (contains? titles "Owner task"))))))

(deftest machine-write-dropped-when-recording-off
  (with-real-auth
    (ensure-recording-off!)
    (let [machine (create-machine-user! *user-id*)
          token (machine-token-for (:id machine) *user-id*)
          before (count (db.task/list-tasks *ds* *user-id* :recent nil))
          resp (API :post "/api/tasks" {:body {:title "Should be dropped"} :token token})
          after (count (db.task/list-tasks *ds* *user-id* :recent nil))]
      (is (= 200 (:status resp)))
      (is (true? (:dropped (:body resp))))
      (is (= before after)))))

(deftest machine-write-persists-when-recording-on
  (with-real-auth
    (ensure-recording-on!)
    (try
      (let [machine (create-machine-user! *user-id*)
            token (machine-token-for (:id machine) *user-id*)
            resp (API :post "/api/tasks" {:body {:title "Real machine task"} :token token})]
        (is (= 201 (:status resp)))
        (let [titles (set (map :title (db.task/list-tasks *ds* *user-id* :recent nil)))]
          (is (contains? titles "Real machine task"))))
      (finally (ensure-recording-off!)))))

(deftest non-machine-writes-ignore-recording-mode
  (ensure-recording-off!)
  (let [resp (API :post "/api/tasks" {:body {:title "UI task"}})]
    (is (= 201 (:status resp)))
    (let [titles (set (map :title (db.task/list-tasks *ds* *user-id* :recent nil)))]
      (is (contains? titles "UI task")))))

(deftest toggle-recording-mode-flips-state-via-api
  (ensure-recording-off!)
  (let [r1 (API :post "/api/recording-mode/toggle" {})
        r2 (API :post "/api/recording-mode/toggle" {})]
    (is (true? (:recording (:body r1))))
    (is (false? (:recording (:body r2))))))

(deftest machine-write-targets-bound-user-not-machine-row
  (with-real-auth
    (ensure-recording-on!)
    (try
      (let [other (db.user/create-user *ds* "other" "otherpass")
            machine (create-machine-user! *user-id*)
            token (machine-token-for (:id machine) *user-id*)
            _ (API :post "/api/tasks" {:body {:title "Bound write"} :token token})
            owner-titles (set (map :title (db.task/list-tasks *ds* *user-id* :recent nil)))
            other-titles (set (map :title (db.task/list-tasks *ds* (:id other) :recent nil)))
            machine-titles (set (map :title (db.task/list-tasks *ds* (:id machine) :recent nil)))]
        (is (contains? owner-titles "Bound write"))
        (is (not (contains? other-titles "Bound write")))
        (is (not (contains? machine-titles "Bound write"))))
      (finally (ensure-recording-off!)))))

(deftest today-board-aggregates-tasks-meets-and-journal-entries
  (let [task (db.task/add-task *ds* *user-id* "Today task")]
    (db.task/set-task-today *ds* *user-id* (:id task) true)
    (let [resp (API :get "/api/today-board" {})]
      (is (= 200 (:status resp)))
      (is (contains? (:body resp) :tasks))
      (is (contains? (:body resp) :meets))
      (is (contains? (:body resp) :journal-entries))
      (is (contains? (set (map :title (:tasks (:body resp)))) "Today task")))))

(deftest add-user-rejects-machine-without-target
  (let [resp (API :post "/api/users"
                  {:as-admin true
                   :body {:username "m" :password "p" :is_machine_user true}})]
    (is (= 400 (:status resp)))))

(deftest add-user-rejects-second-machine-for-same-target
  (create-machine-user! *user-id*)
  (let [resp (API :post "/api/users"
                  {:as-admin true
                   :body {:username "machine2" :password "p"
                          :is_machine_user true :for_user_id *user-id*}})]
    (is (= 409 (:status resp)))))

(deftest add-user-rejects-machine-target-that-is-machine
  (let [machine (create-machine-user! *user-id*)
        resp (API :post "/api/users"
                  {:as-admin true
                   :body {:username "machine2" :password "p"
                          :is_machine_user true :for_user_id (:id machine)}})]
    (is (= 400 (:status resp)))))
