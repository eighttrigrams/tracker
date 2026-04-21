(ns et.tr.server.rest-api.mutations
  (:require [clojure.string :as str]
            [taoensso.telemere :as tel]
            [et.tr.auth :as auth]
            [et.tr.db.task :as db.task]
            [et.tr.db.user :as db.user]
            [et.tr.server.common :as common]
            [et.tr.server.rest-api.middleware :as mw]
            [et.tr.server.rest-api.util :refer [json-response parse-json-body task->api]]))

(defn- user-id [req] (:user-id (:rest-user req)))

(defn login
  "POST /rest/auth/login — issue a JWT for the given {\"username\", \"password\"}.
  Admin uses the ADMIN_PASSWORD env var; regular users authenticate against
  their stored password hash. Always required — the login endpoint is the only
  unauthenticated entry point into the REST API."
  [req]
  (let [{:keys [username password]} (parse-json-body req)]
    (cond
      (or (str/blank? username) (str/blank? password))
        (json-response 400 {:error "username and password are required"})
      (= username "admin")
        (if (= password (common/admin-password))
          (json-response {:token (auth/create-token nil "admin" true false)
                          :user {:id nil :username "admin" :is-admin true}})
          (json-response 401 {:error "Invalid credentials"}))
      :else
        (if-let [user (db.user/verify-user (common/ensure-ds) username password)]
          (let [has-mail (= 1 (:has_mail user))]
            (json-response
              {:token (auth/create-token (:id user) (:username user) false has-mail)
               :user {:id (:id user) :username (:username user) :is-admin false}}))
          (json-response 401 {:error "Invalid credentials"})))))

(defn create-task
  "POST /rest/tasks — create a new task for the authenticated user. JSON body:
  {\"title\" (required), \"scope\" (optional: private|both|work)}. Gated by
  recording mode: when off, the write is logged and dropped with a 201 stub."
  [req]
  (let [body (parse-json-body req)
        title (:title body)
        scope (or (:scope body) "both")]
    (if (str/blank? title)
      (json-response 400 {:error "title is required"})
      (mw/log-and-guard
        "create-task"
        {:user-id (user-id req) :title title :scope scope}
        (json-response 201 {:created true})
        (fn []
          (try (let [task (db.task/add-task (common/ensure-ds) (user-id req) title scope)]
                 (json-response 201 (task->api (assoc task :people [] :places [] :projects [] :goals []))))
               (catch Exception e
                 (tel/log! {:level :error :data {:error (.getMessage e)}} "REST create-task failed")
                 (json-response 500 {:error (.getMessage e)}))))))))

(defn set-task-done
  "PUT /rest/tasks/:id/done — flip the done flag. JSON body: {\"done\" bool}.
  Gated by recording mode."
  [req]
  (try (let [id (Integer/parseInt (get-in req [:params :id]))
             body (parse-json-body req)]
         (if-not (contains? body :done)
           (json-response 400 {:error "done is required"})
           (let [done? (boolean (:done body))]
             (mw/log-and-guard
               "set-task-done"
               {:user-id (user-id req) :task-id id :done done?}
               (json-response {:dropped true})
               (fn []
                 (if-let [result (db.task/set-task-done (common/ensure-ds) (user-id req) id done?)]
                   (json-response result)
                   (json-response 404 {:error "Task not found"})))))))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid task ID"}))))

(defn set-task-today
  "PUT /rest/tasks/:id/today — flip the today flag. JSON body: {\"today\" bool}.
  Gated by recording mode."
  [req]
  (try (let [id (Integer/parseInt (get-in req [:params :id]))
             body (parse-json-body req)]
         (if-not (contains? body :today)
           (json-response 400 {:error "today is required"})
           (let [today? (boolean (:today body))]
             (mw/log-and-guard
               "set-task-today"
               {:user-id (user-id req) :task-id id :today today?}
               (json-response {:dropped true})
               (fn []
                 (if-let [result (db.task/set-task-today (common/ensure-ds) (user-id req) id today?)]
                   (json-response result)
                   (json-response 404 {:error "Task not found"})))))))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid task ID"}))))
