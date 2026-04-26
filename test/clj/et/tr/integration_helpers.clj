(ns et.tr.integration-helpers
  (:require [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [et.tr.db :as db]
            [et.tr.db.user :as db.user]
            [et.tr.server :as server]
            [et.tr.server.common :as common]
            [et.tr.server.recording-mode :as recording-mode]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [clojure.data.json :as json]
            [taoensso.telemere :as tel]))

(tel/remove-handler! :default/console)

(def ^:dynamic *app* nil)
(def ^:dynamic *ds* nil)
(def ^:dynamic *user-id* nil)

(defn make-app []
  (-> server/app-routes
      (wrap-params)
      (wrap-json-body {:keywords? true})
      (recording-mode/wrap-machine-write-guard)
      (wrap-json-response)))

(defn with-integration-db [f]
  (let [conn (db/init-conn {:type :sqlite-memory})]
    (try
      (reset! common/ds conn)
      (reset! common/*config {:dangerously-skip-logins? true})
      (let [user (db.user/create-user conn "test-user" "testpass")]
        (jdbc/execute-one! (db/get-conn conn)
          (sql/format {:update :users :set {:has_mail 1} :where [:= :id (:id user)]}))
        (with-redefs [common/prod-mode? (constantly false)]
          (binding [*app* (make-app)
                    *ds* conn
                    *user-id* (:id user)]
            (f))))
      (finally
        (reset! common/ds nil)
        (reset! common/*config nil)
        (when-let [pc (:persistent-conn conn)]
          (.close pc))))))

(defn GET-json [path]
  (let [resp (*app* (-> (mock/request :get path)
                        (mock/header "X-User-Id" (str *user-id*))))]
    (update resp :body #(when % (json/read-str % :key-fn keyword)))))

(defn POST-json [path body]
  (let [resp (*app* (-> (mock/request :post path)
                        (mock/header "X-User-Id" (str *user-id*))
                        (mock/header "Content-Type" "application/json")
                        (mock/body (json/write-str body))))]
    (update resp :body #(when % (json/read-str % :key-fn keyword)))))

(defn PUT-json [path body]
  (let [resp (*app* (-> (mock/request :put path)
                        (mock/header "X-User-Id" (str *user-id*))
                        (mock/header "Content-Type" "application/json")
                        (mock/body (json/write-str body))))]
    (update resp :body #(when % (json/read-str % :key-fn keyword)))))

(defn DELETE-json
  ([path] (DELETE-json path nil))
  ([path body]
   (let [req (cond-> (-> (mock/request :delete path)
                         (mock/header "X-User-Id" (str *user-id*)))
               body (-> (mock/header "Content-Type" "application/json")
                        (mock/body (json/write-str body))))
         resp (*app* req)]
     (update resp :body #(when % (json/read-str % :key-fn keyword))))))
