(ns et.tr.me-machine-users-integration-test
  "Self-service machine-user endpoints under /api/me/machine-users.
  Only logged-in non-admin human users may manage their own machine
  users; admins, the machine users themselves, and unauthenticated
  callers must be rejected."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [ring.mock.request :as mock]
            [et.tr.auth :as auth]
            [et.tr.db.user :as db.user]
            [et.tr.server.common :as common]
            [et.tr.server.recording-mode :as recording-mode]
            [et.tr.integration-helpers :refer [with-integration-db *app* *ds* *user-id*]]))

(use-fixtures :each with-integration-db)

(defn- with-real-auth* [f]
  (with-redefs [common/allow-skip-logins? (constantly false)]
    (f)))

(defmacro with-real-auth [& body]
  `(with-real-auth* (fn [] ~@body)))

(defn- API
  "Variant of the helper from machine-user-integration-test.

  Auth selection priority:
    :token     — Bearer token (real auth)
    :as-user   — id of a non-admin user, sent via X-User-Id (skip-logins)
    :as-admin  — admin synthetic user (no headers)
    default    — *user-id* via X-User-Id"
  [method path {:keys [body token as-admin as-user]}]
  (let [req (cond-> (mock/request method path)
              token    (mock/header "Authorization" (str "Bearer " token))
              (and (not token) (not as-admin) as-user)
              (mock/header "X-User-Id" (str as-user))
              (and (not token) (not as-admin) (not as-user))
              (mock/header "X-User-Id" (str *user-id*))
              body (-> (mock/header "Content-Type" "application/json")
                       (mock/body (json/write-str body))))]
    (update (*app* req) :body #(when (seq %) (json/read-str % :key-fn keyword)))))

(defn- create-machine-user! [parent-id username password & [{:keys [mail-only]}]]
  (db.user/create-user *ds* username password
                       {:is-machine-user true
                        :for-user-id parent-id
                        :mail-only (boolean mail-only)}))

(defn- machine-token-for [machine-id parent-id]
  (auth/create-token {:user-id machine-id
                      :username "machine"
                      :is-admin false
                      :has-mail false
                      :is-machine-user true
                      :for-user-id parent-id
                      :mail-only false}))

(defn- with-recording-on* [f]
  (let [was-on? (recording-mode/enabled?)]
    (when-not was-on? (recording-mode/toggle!))
    (try (f)
         (finally
           (when-not was-on? (recording-mode/toggle!))))))

(defmacro with-recording-on
  "Mutating /api/* requests from a machine token are short-circuited
  to {:dropped true} by wrap-machine-write-guard when recording is off.
  To exercise the *handler*'s own machine-user rejection (rather than
  the middleware drop), the machine-token rejection assertions need
  recording on so the request actually reaches the handler."
  [& body]
  `(with-recording-on* (fn [] ~@body)))

;; ---- list ------------------------------------------------------------

(deftest list-returns-only-own-machine-users
  (let [other (db.user/create-user *ds* "other-human" "p")
        m1 (create-machine-user! *user-id* "mine-1" "p")
        m2 (create-machine-user! *user-id* "mine-2" "p")
        _foreign (create-machine-user! (:id other) "theirs" "p")
        resp (API :get "/api/me/machine-users" {})
        ids (set (map :id (:body resp)))]
    (is (= 200 (:status resp)))
    (is (= #{(:id m1) (:id m2)} ids))))

(deftest list-rejects-admin
  (let [resp (API :get "/api/me/machine-users" {:as-admin true})]
    (is (= 403 (:status resp)))))

(deftest list-rejects-machine-user
  (with-real-auth
    (let [m (create-machine-user! *user-id* "mach" "p")
          token (machine-token-for (:id m) *user-id*)
          resp (API :get "/api/me/machine-users" {:token token})]
      (is (= 403 (:status resp))))))

(deftest list-rejects-unauthenticated
  (with-real-auth
    (let [resp (API :get "/api/me/machine-users" {})]
      (is (= 403 (:status resp))))))

;; ---- create ----------------------------------------------------------

(deftest create-defaults-mail-only-false-and-forces-for-user-id
  (let [resp (API :post "/api/me/machine-users"
                  {:body {:username "bot1" :password "p"}})
        body (:body resp)]
    (is (= 201 (:status resp)))
    (is (true? (:is_machine_user body)))
    (is (false? (:mail_only body)))
    (is (= *user-id* (:for_user_id body)))))

(deftest create-honors-mail-only-true
  (let [resp (API :post "/api/me/machine-users"
                  {:body {:username "mailbot" :password "p" :mail_only true}})]
    (is (= 201 (:status resp)))
    (is (true? (:mail_only (:body resp))))
    (is (= 1 (:mail_only (db.user/get-user-by-id *ds* (:id (:body resp))))))))

(deftest create-ignores-client-supplied-for-user-id
  (let [other (db.user/create-user *ds* "other-human" "p")
        resp (API :post "/api/me/machine-users"
                  {:body {:username "sneaky" :password "p"
                          :for_user_id (:id other)
                          :is_machine_user false}})]
    (is (= 201 (:status resp)))
    (is (= *user-id* (:for_user_id (:body resp))))
    (is (true? (:is_machine_user (:body resp))))))

(deftest create-requires-username-and-password
  (is (= 400 (:status (API :post "/api/me/machine-users"
                           {:body {:password "p"}}))))
  (is (= 400 (:status (API :post "/api/me/machine-users"
                           {:body {:username "x"}})))))

(deftest create-rejects-username-admin
  (is (= 400 (:status (API :post "/api/me/machine-users"
                           {:body {:username "admin" :password "p"}})))))

(deftest create-rejects-duplicate-username
  (create-machine-user! *user-id* "dup" "p")
  (is (= 409 (:status (API :post "/api/me/machine-users"
                           {:body {:username "dup" :password "p"}})))))

(deftest create-rejects-admin-caller
  (let [resp (API :post "/api/me/machine-users"
                  {:as-admin true
                   :body {:username "x" :password "p"}})]
    (is (= 403 (:status resp)))))

(deftest create-rejects-machine-caller
  (with-real-auth
    (with-recording-on
      (let [m (create-machine-user! *user-id* "mach" "p")
            token (machine-token-for (:id m) *user-id*)
            resp (API :post "/api/me/machine-users"
                      {:token token
                       :body {:username "x" :password "p"}})]
        (is (= 403 (:status resp)))))))

;; ---- update (rename / mail_only) -------------------------------------

(deftest update-renames-machine-user
  (let [m (create-machine-user! *user-id* "old" "p")
        resp (API :put (str "/api/me/machine-users/" (:id m))
                  {:body {:username "new"}})]
    (is (= 200 (:status resp)))
    (is (= "new" (:username (:body resp))))
    (is (= "new" (:username (db.user/get-user-by-id *ds* (:id m)))))))

(deftest update-toggles-mail-only
  (let [m (create-machine-user! *user-id* "m" "p")]
    (is (= 200 (:status (API :put (str "/api/me/machine-users/" (:id m))
                             {:body {:mail_only true}}))))
    (is (= 1 (:mail_only (db.user/get-user-by-id *ds* (:id m)))))
    (is (= 200 (:status (API :put (str "/api/me/machine-users/" (:id m))
                             {:body {:mail_only false}}))))
    (is (= 0 (:mail_only (db.user/get-user-by-id *ds* (:id m)))))))

(deftest update-rejects-empty-body
  (let [m (create-machine-user! *user-id* "m" "p")
        resp (API :put (str "/api/me/machine-users/" (:id m)) {:body {}})]
    (is (= 400 (:status resp)))))

(deftest update-rejects-blank-username
  (let [m (create-machine-user! *user-id* "m" "p")
        resp (API :put (str "/api/me/machine-users/" (:id m))
                  {:body {:username "   "}})]
    (is (= 400 (:status resp)))))

(deftest update-rejects-rename-to-admin
  (let [m (create-machine-user! *user-id* "m" "p")
        resp (API :put (str "/api/me/machine-users/" (:id m))
                  {:body {:username "admin"}})]
    (is (= 400 (:status resp)))))

(deftest update-rejects-duplicate-username
  (create-machine-user! *user-id* "taken" "p")
  (let [m (create-machine-user! *user-id* "free" "p")
        resp (API :put (str "/api/me/machine-users/" (:id m))
                  {:body {:username "taken"}})]
    (is (= 409 (:status resp)))))

(deftest update-rejects-foreign-machine-user
  (testing "404 — no existence leak — when target belongs to someone else"
    (let [other (db.user/create-user *ds* "other" "p")
          theirs (create-machine-user! (:id other) "theirs" "p")
          resp (API :put (str "/api/me/machine-users/" (:id theirs))
                    {:body {:username "hijack"}})]
      (is (= 404 (:status resp)))
      (is (= "theirs" (:username (db.user/get-user-by-id *ds* (:id theirs))))))))

(deftest update-rejects-non-machine-target
  (let [other (db.user/create-user *ds* "human" "p")
        resp (API :put (str "/api/me/machine-users/" (:id other))
                  {:body {:username "x"}})]
    (is (= 404 (:status resp)))))

(deftest update-rejects-admin-and-machine-callers
  (let [m (create-machine-user! *user-id* "m" "p")]
    (is (= 403 (:status (API :put (str "/api/me/machine-users/" (:id m))
                             {:as-admin true
                              :body {:username "x"}}))))
    (with-real-auth
      (with-recording-on
        (let [token (machine-token-for (:id m) *user-id*)]
          (is (= 403 (:status (API :put (str "/api/me/machine-users/" (:id m))
                                   {:token token
                                    :body {:username "x"}})))))))))

;; ---- password rotation ----------------------------------------------

(deftest password-change-lets-machine-log-in-with-new-password
  (with-real-auth
    (let [m (create-machine-user! *user-id* "rotor" "old-pw")
          ;; rotate via skip-logins call — test isolates *just* the
          ;; password change endpoint from the auth gate by re-enabling
          ;; skip-logins for the rotation request.
          rotate-resp (with-redefs [common/allow-skip-logins? (constantly true)]
                        (API :put (str "/api/me/machine-users/" (:id m) "/password")
                             {:body {:password "new-pw"}}))]
      (is (= 200 (:status rotate-resp)))
      (let [old-login (API :post "/api/auth/login"
                           {:body {:username "rotor" :password "old-pw"}})
            new-login (API :post "/api/auth/login"
                           {:body {:username "rotor" :password "new-pw"}})]
        (is (= 401 (:status old-login)))
        (is (= 200 (:status new-login)))))))

(deftest password-change-rejects-blank
  (let [m (create-machine-user! *user-id* "m" "p")
        resp (API :put (str "/api/me/machine-users/" (:id m) "/password")
                  {:body {:password "   "}})]
    (is (= 400 (:status resp)))))

(deftest password-change-rejects-foreign-machine-user
  (let [other (db.user/create-user *ds* "other" "p")
        theirs (create-machine-user! (:id other) "theirs" "old")
        resp (API :put (str "/api/me/machine-users/" (:id theirs) "/password")
                  {:body {:password "hijack"}})]
    (is (= 404 (:status resp)))))

(deftest password-change-rejects-admin-and-machine-callers
  (let [m (create-machine-user! *user-id* "m" "p")]
    (is (= 403 (:status (API :put (str "/api/me/machine-users/" (:id m) "/password")
                             {:as-admin true
                              :body {:password "x"}}))))
    (with-real-auth
      (with-recording-on
        (let [token (machine-token-for (:id m) *user-id*)]
          (is (= 403 (:status (API :put (str "/api/me/machine-users/" (:id m) "/password")
                                   {:token token
                                    :body {:password "x"}})))))))))

;; ---- delete ----------------------------------------------------------

(deftest delete-removes-own-machine-user
  (let [m (create-machine-user! *user-id* "doomed" "p")
        resp (API :delete (str "/api/me/machine-users/" (:id m)) {})]
    (is (= 200 (:status resp)))
    (is (nil? (db.user/get-user-by-id *ds* (:id m))))))

(deftest delete-rejects-foreign-machine-user
  (let [other (db.user/create-user *ds* "other" "p")
        theirs (create-machine-user! (:id other) "theirs" "p")
        resp (API :delete (str "/api/me/machine-users/" (:id theirs)) {})]
    (is (= 404 (:status resp)))
    (is (some? (db.user/get-user-by-id *ds* (:id theirs))))))

(deftest delete-rejects-non-machine-target
  (let [other (db.user/create-user *ds* "human" "p")
        resp (API :delete (str "/api/me/machine-users/" (:id other)) {})]
    (is (= 404 (:status resp)))
    (is (some? (db.user/get-user-by-id *ds* (:id other))))))

(deftest delete-rejects-admin-and-machine-callers
  (let [m (create-machine-user! *user-id* "m" "p")]
    (is (= 403 (:status (API :delete (str "/api/me/machine-users/" (:id m))
                             {:as-admin true}))))
    (is (some? (db.user/get-user-by-id *ds* (:id m))))
    (with-real-auth
      (with-recording-on
        (let [token (machine-token-for (:id m) *user-id*)]
          (is (= 403 (:status (API :delete (str "/api/me/machine-users/" (:id m))
                                   {:token token}))))
          (is (some? (db.user/get-user-by-id *ds* (:id m)))))))))
