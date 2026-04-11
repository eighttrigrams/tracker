(ns et.tr.server
  (:require [ring.adapter.jetty9 :as jetty]
            [et.tr.db :as db]
            [et.tr.server.common :as common]
            [et.tr.server.task-handler :as task-handler]
            [et.tr.server.resource-handler :as resource-handler]
            [et.tr.server.meet-handler :as meet-handler]
            [et.tr.server.meeting-series-handler :as meeting-series-handler]
            [et.tr.server.recurring-task-handler :as recurring-task-handler]
            [et.tr.server.journal-handler :as journal-handler]
            [et.tr.server.journal-entry-handler :as journal-entry-handler]
            [et.tr.server.message-handler :as message-handler]
            [et.tr.server.relation-handler :as relation-handler]
            [et.tr.server.user-handler :as user-handler]
            [et.tr.server.category-handler :as category-handler]
            [et.tr.auth :as auth]
            [et.tr.telegram :as telegram]
            [et.tr.export :as export]
            [et.tr.worker :as worker]
            [et.tr.db.category :as db.category]
            [et.tr.db.task :as db.task]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.tr.middleware.rate-limit :as rate-limit :refer [wrap-rate-limit]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [compojure.core :refer [defroutes GET POST PUT DELETE context]]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cors :refer [wrap-cors]]
            [nrepl.server :as nrepl]
            [taoensso.telemere :as tel])
  (:gen-class))

(defn- env-int [name default]
  (if-let [v (System/getenv name)]
    (try (Integer/parseInt v) (catch Exception _ default))
    default))

(defonce translations-cache (atom nil))

(defn- load-translations []
  (when (nil? @translations-cache)
    (reset! translations-cache
            (edn/read-string (slurp (io/resource "translations.edn")))))
  @translations-cache)

(defn translations-handler [_req]
  {:status 200 :body (load-translations)})

(defn- require-auth [req]
  (if (common/prod-mode?)
    (when-let [token (auth/extract-token req)]
      (auth/verify-token token))
    (common/get-user-from-request req)))

(defn export-data-handler [req]
  (let [user-info (require-auth req)]
    (if-not user-info
      {:status 401 :body {:error "Authentication required"}}
      (try
        (let [user-id (:user-id user-info)
              username (or (:username user-info) "admin")
              data (db/export-all-data (common/ensure-ds) user-id)
              zip-bytes (export/create-export-zip username data)
              timestamp (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd-HHmmss"))
              filename (str "export-" (export/sanitize-filename username) "-" timestamp ".zip")]
          {:status 200
           :headers {"Content-Type" "application/zip"
                     "Content-Disposition" (str "attachment; filename=\"" filename "\"")}
           :body (java.io.ByteArrayInputStream. zip-bytes)})
        (catch Exception e
          {:status 500 :body {:error "Export failed" :message (.getMessage e)}})))))

(defn- reset-test-db-handler [_]
  (if (common/prod-mode?)
    {:status 403 :body {:error "Not available in production"}}
    (do (db/reset-all-data! (common/ensure-ds))
        (rate-limit/reset-rate-limit!)
        {:status 200 :body {:success true}})))

(defn- serve-index [_]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (slurp (io/resource "public/index.html"))})

(defroutes api-routes
  (context "/api" []
    (GET "/translations" [] translations-handler)
    (GET "/export" [] export-data-handler)

    (context "/auth" []
      (GET "/required" [] user-handler/password-required-handler)
      (GET "/available-users" [] user-handler/available-users-handler)
      (POST "/login" [] user-handler/login-handler))

    (context "/user" []
      (PUT "/language" [] user-handler/update-language-handler)
      (PUT "/vim-keys" [] user-handler/update-vim-keys-handler))

    (context "/users" []
      (GET "/" [] user-handler/list-users-handler)
      (POST "/" [] user-handler/add-user-handler)
      (DELETE "/:id" [] user-handler/delete-user-handler))

    (context "/tasks" []
      (GET "/" [] task-handler/list-tasks-handler)
      (GET "/:id" [] task-handler/get-task-handler)
      (POST "/" [] task-handler/add-task-handler)
      (PUT "/:id" [] task-handler/update-task-handler)
      (DELETE "/:id" [] task-handler/delete-task-handler)
      (POST "/:id/categorize" [] task-handler/categorize-task-handler)
      (DELETE "/:id/categorize" [] task-handler/uncategorize-task-handler)
      (POST "/:id/reorder" [] task-handler/reorder-task-handler)
      (PUT "/:id/due-date" [] task-handler/set-due-date-handler)
      (PUT "/:id/due-time" [] task-handler/set-due-time-handler)
      (PUT "/:id/done" [] task-handler/set-task-done-handler)
      (PUT "/:id/scope" [] task-handler/set-task-scope-handler)
      (PUT "/:id/importance" [] task-handler/set-task-importance-handler)
      (PUT "/:id/urgency" [] task-handler/set-task-urgency-handler)
      (PUT "/:id/today" [] task-handler/set-task-today-handler)
      (PUT "/:id/lined-up-for" [] task-handler/set-task-lined-up-for-handler)
      (PUT "/:id/reminder" [] task-handler/set-reminder-handler)
      (PUT "/:id/acknowledge-reminder" [] task-handler/acknowledge-reminder-handler))

    (context "/people" []
      (GET "/" [] category-handler/list-people-handler)
      (POST "/" [] category-handler/add-person-handler)
      (PUT "/:id" [] category-handler/update-person-handler)
      (POST "/:id/reorder" [] (fn [req] (category-handler/reorder-category-handler req db.category/list-people "people"))))

    (context "/places" []
      (GET "/" [] category-handler/list-places-handler)
      (POST "/" [] category-handler/add-place-handler)
      (PUT "/:id" [] category-handler/update-place-handler)
      (POST "/:id/reorder" [] (fn [req] (category-handler/reorder-category-handler req db.category/list-places "places"))))

    (context "/projects" []
      (GET "/" [] category-handler/list-projects-handler)
      (POST "/" [] category-handler/add-project-handler)
      (PUT "/:id" [] category-handler/update-project-handler)
      (POST "/:id/reorder" [] (fn [req] (category-handler/reorder-category-handler req db.category/list-projects "projects"))))

    (context "/goals" []
      (GET "/" [] category-handler/list-goals-handler)
      (POST "/" [] category-handler/add-goal-handler)
      (PUT "/:id" [] category-handler/update-goal-handler)
      (POST "/:id/reorder" [] (fn [req] (category-handler/reorder-category-handler req db.category/list-goals "goals"))))

    (context "/messages" []
      (GET "/" [] message-handler/list-messages-handler)
      (POST "/" [] message-handler/add-message-handler)
      (DELETE "/archived" [] message-handler/delete-all-archived-handler)
      (PUT "/:id/done" [] message-handler/set-message-done-handler)
      (PUT "/:id/annotation" [] message-handler/update-message-annotation-handler)
      (PUT "/:id/scope" [] message-handler/set-message-scope-handler)
      (PUT "/:id/importance" [] message-handler/set-message-importance-handler)
      (PUT "/:id/urgency" [] message-handler/set-message-urgency-handler)
      (POST "/:id/convert-to-resource" [] message-handler/convert-message-to-resource-handler)
      (POST "/:id/convert-to-task" [] message-handler/convert-message-to-task-handler)
      (POST "/:id/merge" [] message-handler/merge-messages-handler)
      (DELETE "/:id" [] message-handler/delete-message-handler))

    (context "/resources" []
      (GET "/" [] resource-handler/list-resources-handler)
      (GET "/:id" [] resource-handler/get-resource-handler)
      (POST "/" [] resource-handler/add-resource-handler)
      (PUT "/:id" [] resource-handler/update-resource-handler)
      (DELETE "/:id" [] resource-handler/delete-resource-handler)
      (POST "/:id/categorize" [] resource-handler/categorize-resource-handler)
      (DELETE "/:id/categorize" [] resource-handler/uncategorize-resource-handler)
      (POST "/:id/reorder" [] resource-handler/reorder-resource-handler)
      (PUT "/:id/scope" [] resource-handler/set-resource-scope-handler)
      (PUT "/:id/importance" [] resource-handler/set-resource-importance-handler))

    (context "/meets" []
      (GET "/" [] meet-handler/list-meets-handler)
      (GET "/:id" [] meet-handler/get-meet-handler)
      (POST "/" [] meet-handler/add-meet-handler)
      (PUT "/:id" [] meet-handler/update-meet-handler)
      (DELETE "/:id" [] meet-handler/delete-meet-handler)
      (POST "/:id/categorize" [] meet-handler/categorize-meet-handler)
      (DELETE "/:id/categorize" [] meet-handler/uncategorize-meet-handler)
      (PUT "/:id/start-date" [] meet-handler/set-meet-start-date-handler)
      (PUT "/:id/start-time" [] meet-handler/set-meet-start-time-handler)
      (PUT "/:id/scope" [] meet-handler/set-meet-scope-handler)
      (PUT "/:id/importance" [] meet-handler/set-meet-importance-handler)
      (PUT "/:id/archive" [] meet-handler/archive-meet-handler))

    (context "/meeting-series" []
      (GET "/" [] meeting-series-handler/list-meeting-series-handler)
      (GET "/:id" [] meeting-series-handler/get-meeting-series-handler)
      (POST "/" [] meeting-series-handler/add-meeting-series-handler)
      (PUT "/:id" [] meeting-series-handler/update-meeting-series-handler)
      (DELETE "/:id" [] meeting-series-handler/delete-meeting-series-handler)
      (POST "/:id/categorize" [] meeting-series-handler/categorize-meeting-series-handler)
      (DELETE "/:id/categorize" [] meeting-series-handler/uncategorize-meeting-series-handler)
      (PUT "/:id/scope" [] meeting-series-handler/set-meeting-series-scope-handler)
      (PUT "/:id/schedule" [] meeting-series-handler/set-meeting-series-schedule-handler)
      (POST "/:id/create-meeting" [] meeting-series-handler/create-next-meeting-handler)
      (GET "/:id/taken-dates" [] meeting-series-handler/get-taken-dates-handler))

    (context "/recurring-tasks" []
      (GET "/" [] recurring-task-handler/list-recurring-tasks-handler)
      (GET "/:id" [] recurring-task-handler/get-recurring-task-handler)
      (POST "/" [] recurring-task-handler/add-recurring-task-handler)
      (PUT "/:id" [] recurring-task-handler/update-recurring-task-handler)
      (DELETE "/:id" [] recurring-task-handler/delete-recurring-task-handler)
      (POST "/:id/categorize" [] recurring-task-handler/categorize-recurring-task-handler)
      (DELETE "/:id/categorize" [] recurring-task-handler/uncategorize-recurring-task-handler)
      (PUT "/:id/scope" [] recurring-task-handler/set-recurring-task-scope-handler)
      (PUT "/:id/schedule" [] recurring-task-handler/set-recurring-task-schedule-handler)
      (POST "/:id/create-task" [] recurring-task-handler/create-next-task-handler)
      (GET "/:id/taken-dates" [] recurring-task-handler/get-taken-dates-handler))

    (context "/journals" []
      (GET "/" [] journal-handler/list-journals-handler)
      (GET "/:id" [] journal-handler/get-journal-handler)
      (POST "/" [] journal-handler/add-journal-handler)
      (PUT "/:id" [] journal-handler/update-journal-handler)
      (DELETE "/:id" [] journal-handler/delete-journal-handler)
      (POST "/:id/categorize" [] journal-handler/categorize-journal-handler)
      (DELETE "/:id/categorize" [] journal-handler/uncategorize-journal-handler)
      (PUT "/:id/scope" [] journal-handler/set-journal-scope-handler)
      (POST "/:id/create-entry" [] journal-handler/create-entry-handler))

    (context "/journal-entries" []
      (GET "/" [] journal-entry-handler/list-journal-entries-handler)
      (GET "/today" [] journal-entry-handler/list-today-journal-entries-handler)
      (GET "/:id" [] journal-entry-handler/get-journal-entry-handler)
      (POST "/" [] journal-entry-handler/add-journal-entry-handler)
      (PUT "/:id" [] journal-entry-handler/update-journal-entry-handler)
      (DELETE "/:id" [] journal-entry-handler/delete-journal-entry-handler)
      (POST "/:id/categorize" [] journal-entry-handler/categorize-journal-entry-handler)
      (DELETE "/:id/categorize" [] journal-entry-handler/uncategorize-journal-entry-handler)
      (POST "/:id/reorder" [] journal-entry-handler/reorder-journal-entry-handler)
      (PUT "/:id/scope" [] journal-entry-handler/set-journal-entry-scope-handler)
      (PUT "/:id/importance" [] journal-entry-handler/set-journal-entry-importance-handler))

    (context "/relations" []
      (POST "/" [] relation-handler/add-relation-handler)
      (DELETE "/" [] relation-handler/delete-relation-handler)
      (GET "/:type/:id" [] relation-handler/get-relations-handler))

    (DELETE "/:category/:id" [] category-handler/delete-category-handler)

    (context "/test" []
      (POST "/reset" [] reset-test-db-handler)
      (POST "/activate-reminders" [] (fn [_]
                                       (if (common/prod-mode?)
                                         {:status 403 :body {:error "Not available in production"}}
                                         (do (doseq [user-id (mapv :id (jdbc/execute! (db/get-conn (common/ensure-ds))
                                                                         (sql/format {:select [:id] :from [:users]})
                                                                         db/jdbc-opts))]
                                               (db.task/activate-reminders! (common/ensure-ds) user-id))
                                             {:status 200 :body {:success true}})))))))

(defroutes app-routes
  api-routes
  (POST "/webhook/telegram" [] (telegram/webhook-handler (common/ensure-ds)))
  (GET "/" [] serve-index)
  (GET "/item/*" [] serve-index)
  (route/resources "/")
  (route/not-found {:status 404 :body {:error "Not found"}}))

(defn- mutating-request? [req]
  (#{:post :put :delete} (:request-method req)))

(defn- public-endpoint? [req]
  (let [uri (:uri req)]
    (= uri "/api/auth/login")))

(defn- wrap-auth [handler prod?]
  (fn [req]
    (if (and prod?
             (mutating-request? req)
             (str/starts-with? (or (:uri req) "") "/api")
             (not (public-endpoint? req)))
      (if-let [token (auth/extract-token req)]
        (if (auth/verify-token token)
          (handler req)
          {:status 401
           :headers {"Content-Type" "application/json"}
           :body "{\"error\":\"Invalid token\"}"})
        {:status 401
         :headers {"Content-Type" "application/json"}
         :body "{\"error\":\"Authentication required\"}"})
      (handler req))))

(defn- app [prod?]
  (-> app-routes
      (wrap-params)
      (wrap-json-body {:keywords? true})
      (wrap-auth prod?)
      (wrap-json-response)
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post :put :delete])
      (wrap-rate-limit (env-int "RATE_LIMIT_MAX_REQUESTS" (if prod? 180 720))
                       (env-int "RATE_LIMIT_WINDOW_SECONDS" 60))))

(defn- run-server [port prod?]
  (let [host (or (System/getenv "HOST") "127.0.0.1")]
    (tel/log! :info (str "Binding to " host ":" port))
    (jetty/run-jetty (app prod?) {:port port :host host :join? false})))

(defn- setup-file-logging [path]
  (let [log-dir (.getParentFile (io/file path))]
    (.mkdirs log-dir)
    (tel/add-handler! :file (tel/handler:file {:path path}))))

(defn -main [& args]
  (reset! common/*config (common/load-config))
  (let [prod? (common/prod-mode?)
        cli-opts (if (map? (first args)) (first args) {})
        e2e? (:e2e cli-opts)]
    (when e2e?
      (if prod?
        (throw (ex-info "Cannot use --e2e in production mode" {}))
        (swap! common/*config merge {:db {:type :sqlite-memory} :dangerously-skip-logins? true})))
    (when-let [logfile (and (not prod?) (:logfile @common/*config))]
      (setup-file-logging logfile))
    (when (and (true? (:dangerously-skip-logins? @common/*config)) prod?)
      (throw (ex-info "Cannot use :dangerously-skip-logins? in production mode" {})))
    (tel/log! :info (str "Starting system in " (if prod? "production" "development") " mode"))
    (common/ensure-ds)
    (when (and (not prod?) (not e2e?))
      (when-let [nrepl-port (:nrepl-port @common/*config)]
        (nrepl/start-server :port nrepl-port)
        (spit ".nrepl-port" nrepl-port)
        (tel/log! :info (str "nREPL server started on port " nrepl-port))))
    (when prod?
      (worker/start-scheduler (common/ensure-ds)))
    (if-let [port (env-int "PORT" (:port @common/*config))]
      (do
        (tel/log! :info (str "Starting server on port " port))
        (run-server port prod?)
        @(promise))
      (throw (ex-info "No port defined" {})))))
