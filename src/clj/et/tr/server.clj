(ns et.tr.server
  (:require [ring.adapter.jetty9 :as jetty]
            [et.tr.db :as db]
            [et.tr.middleware.rate-limit :refer [wrap-rate-limit]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [compojure.core :refer [defroutes GET POST PUT DELETE context]]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cors :refer [wrap-cors]]
            [nrepl.server :as nrepl]
            [buddy.sign.jwt :as jwt])
  (:gen-class))

(defonce ds (atom nil))
(defonce config (atom nil))

(defn- load-config []
  (let [config-file (io/file "config.edn")]
    (if (.exists config-file)
      (do
        (prn "Loading configuration from config.edn")
        (edn/read-string (slurp config-file)))
      (do
        (prn "config.edn not found, using default in-memory database")
        {:db {:type :sqlite-memory}}))))

(defn ensure-ds []
  (when (nil? @ds)
    (when (nil? @config)
      (reset! config (load-config)))
    (let [conn (db/init-conn (get @config :db {:type :sqlite-memory}))]
      (reset! ds conn)))
  @ds)

(defn- prod-mode? []
  (let [on-fly? (some? (System/getenv "FLY_APP_NAME"))
        dev-mode? (= "true" (System/getenv "DEV"))
        admin-pw (System/getenv "ADMIN_PASSWORD")]
    (cond
      (or on-fly? (not dev-mode?))
      (do (when-not admin-pw
            (throw (ex-info "ADMIN_PASSWORD required in production" {})))
          true)
      admin-pw
      true
      :else
      false)))

(defn- allow-skip-logins? []
  (and (true? (:dangerously-skip-logins? @config))
       (not (prod-mode?))))

(defn- jwt-secret []
  (or (System/getenv "ADMIN_PASSWORD") "dev-secret"))

(defn- create-token []
  (jwt/sign {:user "admin"} (jwt-secret)))

(defn- verify-token [token]
  (try
    (jwt/unsign token (jwt-secret))
    (catch Exception _ nil)))

(defn login-handler [req]
  (let [{:keys [email password]} (:body req)]
    (if (allow-skip-logins?)
      {:status 200 :body {:success true :message "No password required"}}
      (if (= email "admin")
        (let [admin-password (if (prod-mode?)
                               (System/getenv "ADMIN_PASSWORD")
                               "admin")]
          (if (= password admin-password)
            {:status 200 :body {:success true :token (create-token)}}
            {:status 401 :body {:success false :error "Invalid credentials"}}))
        {:status 401 :body {:success false :error "Invalid credentials"}}))))

(defn password-required-handler [_req]
  {:status 200 :body {:required (not (allow-skip-logins?))}})

(defn list-tasks-handler [req]
  (let [sort-mode (keyword (get-in req [:params "sort"] "recent"))]
    {:status 200 :body (db/list-tasks (ensure-ds) sort-mode)}))

(defn add-task-handler [req]
  (let [{:keys [title]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [task (db/add-task (ensure-ds) title)]
        {:status 201 :body (assoc task :people [] :places [] :projects [] :goals [])}))))

(defn update-task-handler [req]
  (let [task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [task (db/update-task (ensure-ds) task-id title (or description ""))]
        {:status 200 :body task}))))

(defn list-people-handler [_req]
  {:status 200 :body (db/list-people (ensure-ds))})

(defn add-person-handler [req]
  (let [{:keys [name]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        {:status 201 :body (db/add-person (ensure-ds) name)}
        (catch Exception _
          {:status 409 :body {:success false :error "Person already exists"}})))))

(defn list-places-handler [_req]
  {:status 200 :body (db/list-places (ensure-ds))})

(defn add-place-handler [req]
  (let [{:keys [name]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        {:status 201 :body (db/add-place (ensure-ds) name)}
        (catch Exception _
          {:status 409 :body {:success false :error "Place already exists"}})))))

(defn list-projects-handler [_req]
  {:status 200 :body (db/list-projects (ensure-ds))})

(defn add-project-handler [req]
  (let [{:keys [name]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        {:status 201 :body (db/add-project (ensure-ds) name)}
        (catch Exception _
          {:status 409 :body {:success false :error "Project already exists"}})))))

(defn list-goals-handler [_req]
  {:status 200 :body (db/list-goals (ensure-ds))})

(defn add-goal-handler [req]
  (let [{:keys [name]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        {:status 201 :body (db/add-goal (ensure-ds) name)}
        (catch Exception _
          {:status 409 :body {:success false :error "Goal already exists"}})))))

(defn categorize-task-handler [req]
  (let [task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [category-type category-id]} (:body req)]
    (db/categorize-task (ensure-ds) task-id category-type category-id)
    {:status 200 :body {:success true}}))

(defn uncategorize-task-handler [req]
  (let [task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [category-type category-id]} (:body req)]
    (db/uncategorize-task (ensure-ds) task-id category-type category-id)
    {:status 200 :body {:success true}}))

(defn reorder-task-handler [req]
  (let [task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [target-task-id position]} (:body req)
        all-tasks (db/list-tasks (ensure-ds) :manual)
        target-idx (->> all-tasks
                        (map-indexed vector)
                        (some (fn [[idx task]] (when (= (:id task) target-task-id) idx))))
        target-order (:sort_order (nth all-tasks target-idx))
        neighbor-idx (if (= position "before") (dec target-idx) (inc target-idx))
        neighbor-order (when (and (>= neighbor-idx 0) (< neighbor-idx (count all-tasks)))
                         (:sort_order (nth all-tasks neighbor-idx)))
        new-order (cond
                    (nil? neighbor-order)
                    (if (= position "before")
                      (- target-order 1.0)
                      (+ target-order 1.0))
                    :else
                    (/ (+ target-order neighbor-order) 2.0))]
    (db/reorder-task (ensure-ds) task-id new-order)
    {:status 200 :body {:success true :sort_order new-order}}))

(defn set-due-date-handler [req]
  (let [task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [due-date]} (:body req)
        result (db/set-task-due-date (ensure-ds) task-id due-date)]
    {:status 200 :body result}))

(defroutes api-routes
  (context "/api" []
    (GET "/auth/required" [] password-required-handler)
    (POST "/auth/login" [] login-handler)
    (GET "/tasks" [] list-tasks-handler)
    (POST "/tasks" [] add-task-handler)
    (PUT "/tasks/:id" [] update-task-handler)
    (POST "/tasks/:id/categorize" [] categorize-task-handler)
    (DELETE "/tasks/:id/categorize" [] uncategorize-task-handler)
    (POST "/tasks/:id/reorder" [] reorder-task-handler)
    (PUT "/tasks/:id/due-date" [] set-due-date-handler)
    (GET "/people" [] list-people-handler)
    (POST "/people" [] add-person-handler)
    (GET "/places" [] list-places-handler)
    (POST "/places" [] add-place-handler)
    (GET "/projects" [] list-projects-handler)
    (POST "/projects" [] add-project-handler)
    (GET "/goals" [] list-goals-handler)
    (POST "/goals" [] add-goal-handler)))

(defn- serve-index [_]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (slurp (io/resource "public/index.html"))})

(defroutes app-routes
  api-routes
  (GET "/" [] serve-index)
  (route/resources "/")
  (route/not-found {:status 404 :body {:error "Not found"}}))

(defn- extract-token [req]
  (when-let [auth-header (get-in req [:headers "authorization"])]
    (when (str/starts-with? auth-header "Bearer ")
      (subs auth-header 7))))

(defn- mutating-request? [req]
  (#{:post :put :delete} (:request-method req)))

(defn- public-endpoint? [req]
  (let [uri (:uri req)]
    (= uri "/api/auth/login")))

(defn wrap-auth [handler]
  (fn [req]
    (if (and (prod-mode?)
             (mutating-request? req)
             (str/starts-with? (or (:uri req) "") "/api")
             (not (public-endpoint? req)))
      (if-let [token (extract-token req)]
        (if (verify-token token)
          (handler req)
          {:status 401
           :headers {"Content-Type" "application/json"}
           :body "{\"error\":\"Invalid token\"}"})
        {:status 401
         :headers {"Content-Type" "application/json"}
         :body "{\"error\":\"Authentication required\"}"})
      (handler req))))

(def app
  (-> app-routes
      (wrap-params)
      (wrap-json-body {:keywords? true})
      (wrap-auth)
      (wrap-json-response)
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post :put :delete])
      (wrap-rate-limit)))

(defn- run-server [port]
  (let [host (or (System/getenv "HOST") "127.0.0.1")]
    (prn "Binding to" host ":" port)
    (jetty/run-jetty #'app {:port port :host host :join? false})))

(defn -main [& _args]
  (reset! config (load-config))
  (when (and (true? (:dangerously-skip-logins? @config))
             (prod-mode?))
    (throw (ex-info "Cannot use :dangerously-skip-logins? in production mode" {})))
  (prn (str "Starting system in " (if (prod-mode?) "production" "development") " mode."))
  (ensure-ds)
  (when-not (prod-mode?)
    (let [nrepl-port (Integer/parseInt (or (System/getenv "NREPL_PORT") "7898"))]
      (nrepl/start-server :port nrepl-port)
      (spit ".nrepl-port" nrepl-port)
      (prn "nREPL server started on port" nrepl-port)))
  (let [port (Integer/parseInt (or (System/getenv "PORT") "3027"))]
    (prn "Starting server on port" port)
    (run-server port)
    @(promise)))
