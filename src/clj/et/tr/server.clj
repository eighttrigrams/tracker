(ns et.tr.server
  (:require [ring.adapter.jetty9 :as jetty]
            [et.tr.db :as db]
            [et.tr.auth :as auth]
            [et.tr.telegram :as telegram]
            [et.tr.export :as export]
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
            [taoensso.telemere :as tel])
  (:gen-class))

(defonce ds (atom nil))
(defonce *config (atom nil))

(defn- load-config []
  (let [config-file (io/file "config.edn")]
    (if (.exists config-file)
      (do
        (tel/log! :info "Loading configuration from config.edn")
        (edn/read-string (slurp config-file)))
      (do
        (tel/log! :info "config.edn not found, using defaults")
        {}))))

(defn ensure-ds []
  (when (nil? @ds)
    (when (nil? @*config)
      (reset! *config (load-config)))
    (let [conn (db/init-conn (get @*config :db {:type :sqlite-memory}))]
      (reset! ds conn)))
  @ds)

(defn- env-int [name default]
  (if-let [v (System/getenv name)]
    (try (Integer/parseInt v) (catch Exception _ default))
    default))

(defn prod-mode? []
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
  (and (true? (:dangerously-skip-logins? @*config))
       (not (prod-mode?))))

(defn- get-user-from-request [req]
  (if (allow-skip-logins?)
    (let [user-id-str (get-in req [:headers "x-user-id"])]
      (if (or (nil? user-id-str) (= user-id-str "null"))
        {:user-id nil :is-admin true}
        (let [user-id (Integer/parseInt user-id-str)]
          {:user-id user-id :is-admin false})))
    (when-let [token (auth/extract-token req)]
      (auth/verify-token token))))

(defn- get-user-id [req]
  (:user-id (get-user-from-request req)))

(defn- admin-password []
  (or (System/getenv "ADMIN_PASSWORD") "admin"))

(defn login-handler [req]
  (let [{:keys [username password]} (:body req)]
    (if (allow-skip-logins?)
      (if (= username "admin")
        {:status 200 :body {:success true :user {:id nil :username "admin" :is_admin true :language "en"}}}
        (if-let [user (db/get-user-by-username (ensure-ds) username)]
          {:status 200 :body {:success true :user (dissoc user :password_hash)}}
          {:status 401 :body {:success false :error "User not found"}}))
      (if (= username "admin")
        (if (= password (admin-password))
          {:status 200 :body {:success true
                              :token (auth/create-token nil "admin" true)
                              :user {:id nil :username "admin" :is_admin true :language "en"}}}
          {:status 401 :body {:success false :error "Invalid credentials"}})
        (if-let [user (db/verify-user (ensure-ds) username password)]
          {:status 200 :body {:success true
                              :token (auth/create-token (:id user) (:username user) false)
                              :user user}}
          {:status 401 :body {:success false :error "Invalid credentials"}})))))

(defn password-required-handler [_req]
  {:status 200 :body {:required (not (allow-skip-logins?))}})

(defn available-users-handler [_req]
  (if (allow-skip-logins?)
    (let [users (db/list-users (ensure-ds))
          admin {:id nil :username "admin" :is_admin true :language "en"}]
      {:status 200 :body (cons admin users)})
    {:status 403 :body {:error "Not available in production mode"}}))

(defn- parse-category-param [param]
  (when (and param (not (str/blank? param)))
    (vec (str/split param #","))))

(defn list-tasks-handler [req]
  (let [user-id (get-user-id req)
        sort-mode (keyword (get-in req [:params "sort"] "recent"))
        search-term (get-in req [:params "q"])
        importance (get-in req [:params "importance"])
        context (get-in req [:params "context"])
        strict (= "true" (get-in req [:params "strict"]))
        people (parse-category-param (get-in req [:params "people"]))
        places (parse-category-param (get-in req [:params "places"]))
        projects (parse-category-param (get-in req [:params "projects"]))
        goals (parse-category-param (get-in req [:params "goals"]))
        excluded-places (parse-category-param (get-in req [:params "excluded-places"]))
        excluded-projects (parse-category-param (get-in req [:params "excluded-projects"]))
        categories (when (or people places projects goals)
                     {:people people :places places :projects projects :goals goals})]
    {:status 200 :body (db/list-tasks (ensure-ds) user-id sort-mode {:search-term search-term :importance importance :context context :strict strict :categories categories :excluded-places excluded-places :excluded-projects excluded-projects})}))

(defn add-task-handler [req]
  (let [user-id (get-user-id req)
        {:keys [title scope]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [task (db/add-task (ensure-ds) user-id title (or scope "both"))]
        {:status 201 :body (assoc task :people [] :places [] :projects [] :goals [])}))))

(defn update-task-handler [req]
  (let [user-id (get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [title description tags]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [task (db/update-task (ensure-ds) user-id task-id {:title title :description (or description "") :tags (or tags "")})]
        {:status 200 :body task}))))

(defn list-people-handler [req]
  (let [user-id (get-user-id req)]
    {:status 200 :body (db/list-people (ensure-ds) user-id)}))

(defn add-person-handler [req]
  (let [user-id (get-user-id req)
        {:keys [name]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        {:status 201 :body (db/add-person (ensure-ds) user-id name)}
        (catch Exception _
          {:status 409 :body {:success false :error "Person already exists"}})))))

(defn list-places-handler [req]
  (let [user-id (get-user-id req)]
    {:status 200 :body (db/list-places (ensure-ds) user-id)}))

(defn add-place-handler [req]
  (let [user-id (get-user-id req)
        {:keys [name]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        {:status 201 :body (db/add-place (ensure-ds) user-id name)}
        (catch Exception _
          {:status 409 :body {:success false :error "Place already exists"}})))))

(defn list-projects-handler [req]
  (let [user-id (get-user-id req)]
    {:status 200 :body (db/list-projects (ensure-ds) user-id)}))

(defn add-project-handler [req]
  (let [user-id (get-user-id req)
        {:keys [name]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        {:status 201 :body (db/add-project (ensure-ds) user-id name)}
        (catch Exception _
          {:status 409 :body {:success false :error "Project already exists"}})))))

(defn list-goals-handler [req]
  (let [user-id (get-user-id req)]
    {:status 200 :body (db/list-goals (ensure-ds) user-id)}))

(defn add-goal-handler [req]
  (let [user-id (get-user-id req)
        {:keys [name]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        {:status 201 :body (db/add-goal (ensure-ds) user-id name)}
        (catch Exception _
          {:status 409 :body {:success false :error "Goal already exists"}})))))

(defn update-person-handler [req]
  (let [user-id (get-user-id req)
        person-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [name description]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        (let [result (db/update-person (ensure-ds) user-id person-id name (or description ""))]
          (if result
            {:status 200 :body result}
            {:status 404 :body {:success false :error "Person not found"}}))
        (catch Exception _
          {:status 409 :body {:success false :error "Person with this name already exists"}})))))

(defn update-place-handler [req]
  (let [user-id (get-user-id req)
        place-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [name description]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        (let [result (db/update-place (ensure-ds) user-id place-id name (or description ""))]
          (if result
            {:status 200 :body result}
            {:status 404 :body {:success false :error "Place not found"}}))
        (catch Exception _
          {:status 409 :body {:success false :error "Place with this name already exists"}})))))

(defn update-project-handler [req]
  (let [user-id (get-user-id req)
        project-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [name description]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        (let [result (db/update-project (ensure-ds) user-id project-id name (or description ""))]
          (if result
            {:status 200 :body result}
            {:status 404 :body {:success false :error "Project not found"}}))
        (catch Exception _
          {:status 409 :body {:success false :error "Project with this name already exists"}})))))

(defn update-goal-handler [req]
  (let [user-id (get-user-id req)
        goal-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [name description]} (:body req)]
    (if (str/blank? name)
      {:status 400 :body {:success false :error "Name is required"}}
      (try
        (let [result (db/update-goal (ensure-ds) user-id goal-id name (or description ""))]
          (if result
            {:status 200 :body result}
            {:status 404 :body {:success false :error "Goal not found"}}))
        (catch Exception _
          {:status 409 :body {:success false :error "Goal with this name already exists"}})))))

(def category-config
  {"people" {:type "person" :table "people"}
   "places" {:type "place" :table "places"}
   "projects" {:type "project" :table "projects"}
   "goals" {:type "goal" :table "goals"}})

(defn delete-category-handler [req]
  (let [user-id (get-user-id req)
        category-id (Integer/parseInt (get-in req [:params :id]))
        category-key (get-in req [:params :category])
        {:keys [type table]} (get category-config category-key)]
    (if-not type
      {:status 400 :body {:success false :error "Invalid category type"}}
      (let [result (db/delete-category (ensure-ds) user-id category-id type table)]
        (if (:success result)
          {:status 200 :body {:success true}}
          {:status 404 :body {:success false :error (str (str/capitalize type) " not found")}})))))

(defn reorder-category-handler [req list-fn table-name]
  (let [user-id (get-user-id req)
        category-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [target-category-id position]} (:body req)
        all-categories (list-fn (ensure-ds) user-id)
        target-idx (->> all-categories
                        (map-indexed vector)
                        (some (fn [[idx cat]] (when (= (:id cat) target-category-id) idx))))
        target-order (:sort_order (nth all-categories target-idx))
        neighbor-idx (if (= position "before") (dec target-idx) (inc target-idx))
        neighbor-order (when (and (>= neighbor-idx 0) (< neighbor-idx (count all-categories)))
                         (:sort_order (nth all-categories neighbor-idx)))
        new-order (cond
                    (nil? neighbor-order)
                    (if (= position "before")
                      (- target-order 1.0)
                      (+ target-order 1.0))
                    :else
                    (/ (+ target-order neighbor-order) 2.0))]
    (db/reorder-category (ensure-ds) user-id category-id new-order table-name)
    {:status 200 :body {:success true :sort_order new-order}}))

(defn categorize-task-handler [req]
  (let [user-id (get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [category-type category-id]} (:body req)]
    (db/categorize-task (ensure-ds) user-id task-id category-type category-id)
    {:status 200 :body {:success true}}))

(defn uncategorize-task-handler [req]
  (let [user-id (get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [category-type category-id]} (:body req)]
    (db/uncategorize-task (ensure-ds) user-id task-id category-type category-id)
    {:status 200 :body {:success true}}))

(defn reorder-task-handler [req]
  (let [user-id (get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [target-task-id position]} (:body req)
        all-tasks (db/list-tasks (ensure-ds) user-id :manual)
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
    (db/reorder-task (ensure-ds) user-id task-id new-order)
    {:status 200 :body {:success true :sort_order new-order}}))

(defn set-due-date-handler [req]
  (let [user-id (get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [due-date]} (:body req)
        result (db/set-task-due-date (ensure-ds) user-id task-id due-date)]
    {:status 200 :body result}))

(defn valid-time-format? [time-str]
  (or (nil? time-str)
      (empty? time-str)
      (re-matches #"^([01]\d|2[0-3]):([0-5]\d)$" time-str)))

(defn set-due-time-handler [req]
  (let [user-id (get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        {:keys [due-time]} (:body req)]
    (if (valid-time-format? due-time)
      (let [result (db/set-task-due-time (ensure-ds) user-id task-id due-time)]
        {:status 200 :body result})
      {:status 400 :body {:error "Invalid time format. Use HH:MM (24-hour format)"}})))

(defn set-task-done-handler [req]
  (if-not (contains? (:body req) :done)
    {:status 400 :body {:error "Missing required field: done"}}
    (let [user-id (get-user-id req)
          task-id (Integer/parseInt (get-in req [:params :id]))
          done? (boolean (get-in req [:body :done]))
          result (db/set-task-done (ensure-ds) user-id task-id done?)]
      (if result
        {:status 200 :body result}
        {:status 404 :body {:error "Task not found"}}))))

(defn- make-task-property-handler [field valid-values error-message]
  (fn [req]
    (let [value (get-in req [:body field])]
      (if-not (contains? valid-values value)
        {:status 400 :body {:error error-message}}
        (let [user-id (get-user-id req)
              task-id (Integer/parseInt (get-in req [:params :id]))
              result (db/set-task-field (ensure-ds) user-id task-id field value)]
          (if result
            {:status 200 :body result}
            {:status 404 :body {:error "Task not found"}}))))))

(def set-task-scope-handler
  (make-task-property-handler :scope db/valid-scopes
                              "Invalid scope. Must be 'private', 'both', or 'work'"))

(def set-task-importance-handler
  (make-task-property-handler :importance db/valid-importances
                              "Invalid importance. Must be 'normal', 'important', or 'critical'"))

(def set-task-urgency-handler
  (make-task-property-handler :urgency db/valid-urgencies
                              "Invalid urgency. Must be 'default', 'urgent', or 'superurgent'"))

(defn delete-task-handler [req]
  (let [user-id (get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        result (db/delete-task (ensure-ds) user-id task-id)]
    (if (:success result)
      {:status 200 :body {:success true}}
      {:status 404 :body {:success false :error "Task not found"}})))

(defn- is-admin? [req]
  (:is-admin (get-user-from-request req)))

(defmacro with-admin-message-context
  [req user-id-sym message-id-sym & body]
  `(if (is-admin? ~req)
     (let [~user-id-sym (get-user-id ~req)
           ~message-id-sym (Integer/parseInt (get-in ~req [:params :id]))]
       ~@body)
     {:status 403 :body {:error "Admin access required"}}))

(defn list-users-handler [req]
  (if (is-admin? req)
    {:status 200 :body (db/list-users (ensure-ds))}
    {:status 403 :body {:error "Admin access required"}}))

(defn add-user-handler [req]
  (if (is-admin? req)
    (let [{:keys [username password]} (:body req)]
      (if (or (str/blank? username) (str/blank? password))
        {:status 400 :body {:error "Username and password are required"}}
        (if (= username "admin")
          {:status 400 :body {:error "Cannot create user named 'admin'"}}
          (try
            (let [user (db/create-user (ensure-ds) username password)]
              {:status 201 :body (dissoc user :password_hash)})
            (catch Exception _
              {:status 409 :body {:error "Username already exists"}})))))
    {:status 403 :body {:error "Admin access required"}}))

(defn delete-user-handler [req]
  (if (is-admin? req)
    (let [user-id (Integer/parseInt (get-in req [:params :id]))
          result (db/delete-user (ensure-ds) user-id)]
      (if (:success result)
        {:status 200 :body {:success true}}
        {:status 404 :body {:error "User not found"}}))
    {:status 403 :body {:error "Admin access required"}}))

(def valid-languages #{"en" "de" "pt"})

(defn update-language-handler [req]
  (let [user-info (get-user-from-request req)
        user-id (:user-id user-info)
        {:keys [language]} (:body req)]
    (cond
      (:is-admin user-info)
      {:status 400 :body {:error "Admin language cannot be changed"}}

      (nil? user-id)
      {:status 400 :body {:error "User not found"}}

      (not (contains? valid-languages language))
      {:status 400 :body {:error "Invalid language"}}

      :else
      (if-let [result (db/set-user-language (ensure-ds) user-id language)]
        {:status 200 :body result}
        {:status 404 :body {:error "User not found"}}))))

(defn list-messages-handler [req]
  (if (is-admin? req)
    (let [user-id (get-user-id req)
          sort-mode (keyword (get-in req [:params "sort"] "recent"))
          sender (get-in req [:params "sender"])
          excluded-senders-param (get-in req [:params "excludedSenders"])
          excluded-senders (when (and excluded-senders-param (not (str/blank? excluded-senders-param)))
                             (set (str/split excluded-senders-param #",")))]
      {:status 200 :body (db/list-messages (ensure-ds) user-id {:sort-mode sort-mode
                                                                 :sender-filter sender
                                                                 :excluded-senders excluded-senders})})
    {:status 403 :body {:error "Admin access required"}}))

(defn add-message-handler [req]
  (if (is-admin? req)
    (let [user-id (get-user-id req)
          {:keys [sender title description]} (:body req)]
      (cond
        (str/blank? sender)
        {:status 400 :body {:success false :error "Sender is required"}}

        (str/blank? title)
        {:status 400 :body {:success false :error "Title is required"}}

        :else
        (let [message (db/add-message (ensure-ds) user-id sender title description)]
          {:status 201 :body message})))
    {:status 403 :body {:error "Admin access required"}}))

(defn set-message-done-handler [req]
  (if-not (contains? (:body req) :done)
    {:status 400 :body {:error "Missing required field: done"}}
    (with-admin-message-context req user-id message-id
      (let [done? (boolean (get-in req [:body :done]))
            result (db/set-message-done (ensure-ds) user-id message-id done?)]
        (if result
          {:status 200 :body result}
          {:status 404 :body {:error "Message not found"}})))))

(defn delete-message-handler [req]
  (with-admin-message-context req user-id message-id
    (let [result (db/delete-message (ensure-ds) user-id message-id)]
      (if (:success result)
        {:status 200 :body {:success true}}
        {:status 404 :body {:success false :error "Message not found"}}))))

(defn update-message-annotation-handler [req]
  (with-admin-message-context req user-id message-id
    (let [annotation (get-in req [:body :annotation])
          result (db/update-message-annotation (ensure-ds) user-id message-id annotation)]
      (if result
        {:status 200 :body result}
        {:status 404 :body {:error "Message not found"}}))))

(defonce translations-cache (atom nil))

(defn- load-translations []
  (when (nil? @translations-cache)
    (reset! translations-cache
            (edn/read-string (slurp (io/resource "translations.edn")))))
  @translations-cache)

(defn translations-handler [_req]
  {:status 200 :body (load-translations)})

(defn- require-auth [req]
  (if (prod-mode?)
    (when-let [token (auth/extract-token req)]
      (auth/verify-token token))
    (get-user-from-request req)))

(defn export-data-handler [req]
  (let [user-info (require-auth req)]
    (if-not user-info
      {:status 401 :body {:error "Authentication required"}}
      (try
        (let [user-id (:user-id user-info)
              username (or (:username user-info) "admin")
              data (db/export-all-data (ensure-ds) user-id)
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
  (if (prod-mode?)
    {:status 403 :body {:error "Not available in production"}}
    (do (db/reset-all-data! (ensure-ds))
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
      (GET "/required" [] password-required-handler)
      (GET "/available-users" [] available-users-handler)
      (POST "/login" [] login-handler))

    (context "/user" []
      (PUT "/language" [] update-language-handler))

    (context "/users" []
      (GET "/" [] list-users-handler)
      (POST "/" [] add-user-handler)
      (DELETE "/:id" [] delete-user-handler))

    (context "/tasks" []
      (GET "/" [] list-tasks-handler)
      (POST "/" [] add-task-handler)
      (PUT "/:id" [] update-task-handler)
      (DELETE "/:id" [] delete-task-handler)
      (POST "/:id/categorize" [] categorize-task-handler)
      (DELETE "/:id/categorize" [] uncategorize-task-handler)
      (POST "/:id/reorder" [] reorder-task-handler)
      (PUT "/:id/due-date" [] set-due-date-handler)
      (PUT "/:id/due-time" [] set-due-time-handler)
      (PUT "/:id/done" [] set-task-done-handler)
      (PUT "/:id/scope" [] set-task-scope-handler)
      (PUT "/:id/importance" [] set-task-importance-handler)
      (PUT "/:id/urgency" [] set-task-urgency-handler))

    (context "/people" []
      (GET "/" [] list-people-handler)
      (POST "/" [] add-person-handler)
      (PUT "/:id" [] update-person-handler)
      (POST "/:id/reorder" [] (fn [req] (reorder-category-handler req db/list-people "people"))))

    (context "/places" []
      (GET "/" [] list-places-handler)
      (POST "/" [] add-place-handler)
      (PUT "/:id" [] update-place-handler)
      (POST "/:id/reorder" [] (fn [req] (reorder-category-handler req db/list-places "places"))))

    (context "/projects" []
      (GET "/" [] list-projects-handler)
      (POST "/" [] add-project-handler)
      (PUT "/:id" [] update-project-handler)
      (POST "/:id/reorder" [] (fn [req] (reorder-category-handler req db/list-projects "projects"))))

    (context "/goals" []
      (GET "/" [] list-goals-handler)
      (POST "/" [] add-goal-handler)
      (PUT "/:id" [] update-goal-handler)
      (POST "/:id/reorder" [] (fn [req] (reorder-category-handler req db/list-goals "goals"))))

    (context "/messages" []
      (GET "/" [] list-messages-handler)
      (POST "/" [] add-message-handler)
      (PUT "/:id/done" [] set-message-done-handler)
      (PUT "/:id/annotation" [] update-message-annotation-handler)
      (DELETE "/:id" [] delete-message-handler))

    (DELETE "/:category/:id" [] delete-category-handler)

    (context "/test" []
      (POST "/reset" [] reset-test-db-handler))))

(defroutes app-routes
  api-routes
  (POST "/webhook/telegram" [] (telegram/webhook-handler (ensure-ds)))
  (GET "/" [] serve-index)
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
  (reset! *config (load-config))
  (let [prod? (prod-mode?)
        cli-opts (if (map? (first args)) (first args) {})
        e2e? (:e2e cli-opts)]
    (when e2e?
      (if prod?
        (throw (ex-info "Cannot use --e2e in production mode" {}))
        (swap! *config merge {:db {:type :sqlite-memory} :dangerously-skip-logins? true})))
    (when-let [logfile (and (not prod?) (:logfile @*config))]
      (setup-file-logging logfile))
    (when (and (true? (:dangerously-skip-logins? @*config)) prod?)
      (throw (ex-info "Cannot use :dangerously-skip-logins? in production mode" {})))
    (tel/log! :info (str "Starting system in " (if prod? "production" "development") " mode"))
    (ensure-ds)
    (when (and (not prod?) (not e2e?))
      (when-let [nrepl-port (:nrepl-port @*config)] 
        (nrepl/start-server :port nrepl-port)
        (spit ".nrepl-port" nrepl-port)
        (tel/log! :info (str "nREPL server started on port " nrepl-port))))
    (if-let [port (env-int "PORT" (:port @*config))]
      (do
        (tel/log! :info (str "Starting server on port " port))
        (run-server port prod?)
        @(promise))
      (throw (ex-info "No port defined" {})))))
