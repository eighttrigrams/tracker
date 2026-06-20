(ns et.tr.machine-default-limit-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [ring.mock.request :as mock]
            [et.tr.auth :as auth]
            [et.tr.db.user :as db.user]
            [et.tr.db.meet :as db.meet]
            [et.tr.server.common :as common]
            [et.tr.integration-helpers :refer [with-integration-db *app* *ds* *user-id*]]))

(use-fixtures :each with-integration-db)

(defn- with-real-auth* [f]
  (with-redefs [common/allow-skip-logins? (constantly false)]
    (f)))

(defmacro with-real-auth [& body]
  `(with-real-auth* (fn [] ~@body)))

(defn- machine-token [machine-id target-id]
  (auth/create-token {:user-id machine-id
                      :username "machine"
                      :is-admin false
                      :has-mail false
                      :is-machine-user true
                      :for-user-id target-id
                      :mail-only false}))

(defn- get-meets [opts]
  (let [{:keys [token path]} opts
        path (or path "/api/meets")
        req (cond-> (mock/request :get path)
              token       (mock/header "Authorization" (str "Bearer " token))
              (not token) (mock/header "X-User-Id" (str *user-id*)))]
    (-> (*app* req)
        (update :body #(when (seq %) (json/read-str % :key-fn keyword))))))

(defn- seed-meets! [n]
  (dotimes [i n]
    (db.meet/add-meet *ds* *user-id* (str "Meet " i))))

(deftest machine-user-no-limit-defaults-to-hundred
  (with-real-auth
    (let [machine (db.user/create-user *ds* "machine" "machinepass"
                                       {:is-machine-user true :for-user-id *user-id*})
          token (machine-token (:id machine) *user-id*)]
      (seed-meets! 120)
      (testing "machine user without ?limit gets 100 rows"
        (let [resp (get-meets {:token token})]
          (is (= 200 (:status resp)))
          (is (= 100 (count (:body resp)))))))))

(deftest machine-user-explicit-limit-overrides-default
  (with-real-auth
    (let [machine (db.user/create-user *ds* "machine" "machinepass"
                                       {:is-machine-user true :for-user-id *user-id*})
          token (machine-token (:id machine) *user-id*)]
      (seed-meets! 25)
      (testing "machine user with ?limit=20 gets 20 rows"
        (let [resp (get-meets {:token token :path "/api/meets?limit=20"})]
          (is (= 200 (:status resp)))
          (is (= 20 (count (:body resp))))))
      (testing "machine user with ?limit=3 gets 3 rows"
        (let [resp (get-meets {:token token :path "/api/meets?limit=3"})]
          (is (= 3 (count (:body resp)))))))))

(deftest regular-user-not-capped
  (seed-meets! 25)
  (testing "regular user gets all rows when no ?limit"
    (let [resp (get-meets {})]
      (is (= 200 (:status resp)))
      (is (= 25 (count (:body resp))))))
  (testing "regular user can still pass ?limit explicitly"
    (let [resp (get-meets {:path "/api/meets?limit=5"})]
      (is (= 5 (count (:body resp)))))))

(deftest single-resource-get-not-affected
  (with-real-auth
    (let [machine (db.user/create-user *ds* "machine" "machinepass"
                                       {:is-machine-user true :for-user-id *user-id*})
          token (machine-token (:id machine) *user-id*)
          meet (db.meet/add-meet *ds* *user-id* "Solo meet")]
      (testing "GET /api/meets/:id returns the single resource even for machine"
        (let [resp (get-meets {:token token :path (str "/api/meets/" (:id meet))})]
          (is (= 200 (:status resp)))
          (is (= "Solo meet" (:title (:body resp)))))))))
