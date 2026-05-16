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

(defn- machine-token-for
  ([machine-id target-id] (machine-token-for machine-id target-id false))
  ([machine-id target-id has-mail?]
   (machine-token-for machine-id target-id has-mail? false))
  ([machine-id target-id has-mail? mail-only?]
   (auth/create-token {:user-id machine-id
                       :username "machine"
                       :is-admin false
                       :has-mail has-mail?
                       :is-machine-user true
                       :for-user-id target-id
                       :mail-only mail-only?})))

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

(deftest machine-message-post-bypasses-recording-mode
  (with-real-auth
    (ensure-recording-off!)
    (let [machine (create-machine-user! *user-id*)
          token (machine-token-for (:id machine) *user-id* true)
          resp (API :post "/api/messages"
                    {:body {:sender "auto" :title "from-machine" :description "body"}
                     :token token})]
      (is (= 201 (:status resp)) "POST /api/messages must bypass the gate"))))

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

(deftest add-user-allows-multiple-machines-for-same-target
  (create-machine-user! *user-id*)
  (let [resp (API :post "/api/users"
                  {:as-admin true
                   :body {:username "machine2" :password "p"
                          :is_machine_user true :for_user_id *user-id*}})]
    (is (= 201 (:status resp)))
    (is (true? (:is_machine_user (:body resp))))
    (is (= *user-id* (:for_user_id (:body resp))))
    (let [list-resp (API :get "/api/users" {:as-admin true})
          machines (->> (:body list-resp)
                        (filter #(= *user-id* (:for_user_id %))))]
      (is (= 2 (count machines))))))

(deftest add-user-rejects-machine-target-that-is-machine
  (let [machine (create-machine-user! *user-id*)
        resp (API :post "/api/users"
                  {:as-admin true
                   :body {:username "machine2" :password "p"
                          :is_machine_user true :for_user_id (:id machine)}})]
    (is (= 400 (:status resp)))))

(deftest add-user-persists-mail-only-flag
  (let [resp (API :post "/api/users"
                  {:as-admin true
                   :body {:username "mailbot" :password "p"
                          :is_machine_user true :for_user_id *user-id*
                          :mail_only true}})]
    (is (= 201 (:status resp)))
    (is (true? (:mail_only (:body resp))))
    (let [persisted (db.user/get-user-by-id *ds* (:id (:body resp)))]
      (is (= 1 (:mail_only persisted))))))

(deftest add-user-mail-only-defaults-to-false-when-omitted
  (let [resp (API :post "/api/users"
                  {:as-admin true
                   :body {:username "regularbot" :password "p"
                          :is_machine_user true :for_user_id *user-id*}})]
    (is (= 201 (:status resp)))
    (is (false? (:mail_only (:body resp))))))

(deftest add-user-mail-only-ignored-for-non-machine-users
  (let [resp (API :post "/api/users"
                  {:as-admin true
                   :body {:username "human" :password "p" :mail_only true}})]
    (is (= 201 (:status resp)))
    (is (false? (boolean (:mail_only (:body resp)))))))

(deftest mail-only-machine-write-blocked-even-when-recording-on
  (with-real-auth
    (ensure-recording-on!)
    (try
      (let [machine (db.user/create-user *ds* "mailbot" "p"
                                         {:is-machine-user true
                                          :for-user-id *user-id*
                                          :mail-only true})
            token (machine-token-for (:id machine) *user-id* true true)
            before (count (db.task/list-tasks *ds* *user-id* :recent nil))
            resp (API :post "/api/tasks"
                      {:body {:title "should not land"} :token token})
            after (count (db.task/list-tasks *ds* *user-id* :recent nil))]
        (is (= 200 (:status resp)))
        (is (true? (:dropped (:body resp))))
        (is (= before after)))
      (finally (ensure-recording-off!)))))

(deftest mail-only-machine-message-still-bypasses-recording-mode
  (with-real-auth
    (ensure-recording-off!)
    (let [machine (db.user/create-user *ds* "mailbot" "p"
                                       {:is-machine-user true
                                        :for-user-id *user-id*
                                        :mail-only true})
          token (machine-token-for (:id machine) *user-id* true true)
          resp (API :post "/api/messages"
                    {:body {:sender "auto" :title "from-mailbot" :description "body"}
                     :token token})]
      (is (= 201 (:status resp))
          "POST /api/messages must always pass for mail-only machine users"))))

(deftest mail-only-claim-survives-login-roundtrip
  (with-real-auth
    (db.user/create-user *ds* "mailbot" "secret"
                         {:is-machine-user true
                          :for-user-id *user-id*
                          :mail-only true})
    (let [resp (API :post "/api/auth/login"
                    {:body {:username "mailbot" :password "secret"}})
          token (:token (:body resp))
          claims (auth/verify-token token)]
      (is (= 200 (:status resp)))
      (is (true? (:is-machine-user claims)))
      (is (true? (:mail-only claims))))))

(deftest non-mail-only-machine-claim-has-mail-only-false
  (with-real-auth
    (db.user/create-user *ds* "fullbot" "secret"
                         {:is-machine-user true
                          :for-user-id *user-id*
                          :mail-only false})
    (let [resp (API :post "/api/auth/login"
                    {:body {:username "fullbot" :password "secret"}})
          token (:token (:body resp))
          claims (auth/verify-token token)]
      (is (= 200 (:status resp)))
      (is (true? (:is-machine-user claims)))
      (is (false? (:mail-only claims))))))

(deftest two-machine-users-for-same-target-can-both-write-when-recording-on
  (with-real-auth
    (ensure-recording-on!)
    (try
      (let [m1 (db.user/create-user *ds* "machine1" "p"
                                    {:is-machine-user true :for-user-id *user-id*})
            m2 (db.user/create-user *ds* "machine2" "p"
                                    {:is-machine-user true :for-user-id *user-id*})
            t1 (machine-token-for (:id m1) *user-id*)
            t2 (machine-token-for (:id m2) *user-id*)
            r1 (API :post "/api/tasks" {:body {:title "from m1"} :token t1})
            r2 (API :post "/api/tasks" {:body {:title "from m2"} :token t2})
            titles (set (map :title (db.task/list-tasks *ds* *user-id* :recent nil)))]
        (is (= 201 (:status r1)))
        (is (= 201 (:status r2)))
        (is (contains? titles "from m1"))
        (is (contains? titles "from m2")))
      (finally (ensure-recording-off!)))))
