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
  (:import [java.util.zip ZipOutputStream ZipEntry]
           [java.io ByteArrayOutputStream]
           [java.text Normalizer Normalizer$Form]
           [java.nio.charset StandardCharsets])
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
  (and (true? (:dangerously-skip-logins? @config))
       (not (prod-mode?))))

(defn- jwt-secret []
  (or (System/getenv "ADMIN_PASSWORD") "dev-secret"))

(defn- create-token [user-id username is-admin]
  (jwt/sign {:user-id user-id :username username :is-admin is-admin} (jwt-secret)))

(defn- verify-token [token]
  (try
    (jwt/unsign token (jwt-secret))
    (catch Exception _ nil)))

(defn- extract-token [req]
  (when-let [auth-header (get-in req [:headers "authorization"])]
    (when (str/starts-with? auth-header "Bearer ")
      (subs auth-header 7))))

(defn- get-user-from-request [req]
  (if (allow-skip-logins?)
    (let [user-id-str (get-in req [:headers "x-user-id"])]
      (if (or (nil? user-id-str) (= user-id-str "null"))
        {:user-id nil :is-admin true}
        (let [user-id (Integer/parseInt user-id-str)]
          {:user-id user-id :is-admin false})))
    (when-let [token (extract-token req)]
      (verify-token token))))

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
                              :token (create-token nil "admin" true)
                              :user {:id nil :username "admin" :is_admin true :language "en"}}}
          {:status 401 :body {:success false :error "Invalid credentials"}})
        (if-let [user (db/verify-user (ensure-ds) username password)]
          {:status 200 :body {:success true
                              :token (create-token (:id user) (:username user) false)
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

(defn list-tasks-handler [req]
  (let [user-id (get-user-id req)
        sort-mode (keyword (get-in req [:params "sort"] "recent"))]
    {:status 200 :body (db/list-tasks (ensure-ds) user-id sort-mode)}))

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
        {:keys [title description]} (:body req)]
    (if (str/blank? title)
      {:status 400 :body {:success false :error "Title is required"}}
      (let [task (db/update-task (ensure-ds) user-id task-id title (or description ""))]
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

(defn set-task-scope-handler [req]
  (let [scope (get-in req [:body :scope])]
    (if-not (contains? db/valid-scopes scope)
      {:status 400 :body {:error "Invalid scope. Must be 'private', 'both', or 'work'"}}
      (let [user-id (get-user-id req)
            task-id (Integer/parseInt (get-in req [:params :id]))
            result (db/set-task-scope (ensure-ds) user-id task-id scope)]
        (if result
          {:status 200 :body result}
          {:status 404 :body {:error "Task not found"}})))))

(defn set-task-importance-handler [req]
  (let [importance (get-in req [:body :importance])]
    (if-not (contains? db/valid-importances importance)
      {:status 400 :body {:error "Invalid importance. Must be 'normal', 'important', or 'critical'"}}
      (let [user-id (get-user-id req)
            task-id (Integer/parseInt (get-in req [:params :id]))
            result (db/set-task-importance (ensure-ds) user-id task-id importance)]
        (if result
          {:status 200 :body result}
          {:status 404 :body {:error "Task not found"}})))))

(defn delete-task-handler [req]
  (let [user-id (get-user-id req)
        task-id (Integer/parseInt (get-in req [:params :id]))
        result (db/delete-task (ensure-ds) user-id task-id)]
    (if (:success result)
      {:status 200 :body {:success true}}
      {:status 404 :body {:success false :error "Task not found"}})))

(defn- is-admin? [req]
  (:is-admin (get-user-from-request req)))

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

(defonce translations-cache (atom nil))

(defn- load-translations []
  (when (nil? @translations-cache)
    (reset! translations-cache
            (edn/read-string (slurp (io/resource "translations.edn")))))
  @translations-cache)

(defn translations-handler [_req]
  {:status 200 :body (load-translations)})

(defn- sanitize-filename [s]
  (let [s (or s "")
        normalized (Normalizer/normalize s Normalizer$Form/NFD)
        ascii-only (str/replace normalized #"[\p{M}]" "")
        sanitized (-> ascii-only
                      (str/replace #"\x00" "")
                      (str/replace #"[/\\:*?\"<>|]" "_")
                      (str/replace #"[^\x20-\x7E]" "_")
                      (str/replace #"\s+" "-"))
        final-str (if (str/blank? sanitized) "untitled" sanitized)]
    (subs final-str 0 (min (count final-str) 50))))

(defn- task-to-markdown [task]
  (let [frontmatter (str "---\n"
                         "id: " (:id task) "\n"
                         "created_at: \"" (:created_at task) "\"\n"
                         "modified_at: \"" (:modified_at task) "\"\n"
                         (when (:due_date task) (str "due_date: \"" (:due_date task) "\"\n"))
                         (when (:due_time task) (str "due_time: \"" (:due_time task) "\"\n"))
                         "done: " (if (= 1 (:done task)) "true" "false") "\n"
                         "scope: \"" (:scope task) "\"\n"
                         "importance: \"" (:importance task) "\"\n"
                         "sort_order: " (:sort_order task) "\n"
                         (when (seq (:people task)) (str "people: " (pr-str (:people task)) "\n"))
                         (when (seq (:places task)) (str "places: " (pr-str (:places task)) "\n"))
                         (when (seq (:projects task)) (str "projects: " (pr-str (:projects task)) "\n"))
                         (when (seq (:goals task)) (str "goals: " (pr-str (:goals task)) "\n"))
                         "---\n\n")
        title (str "# " (:title task) "\n\n")
        description (or (:description task) "")]
    (str frontmatter title description)))

(defn- create-export-zip [username data]
  (let [baos (ByteArrayOutputStream.)
        timestamp (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd-HHmmss"))]
    (with-open [zos (ZipOutputStream. baos StandardCharsets/UTF_8)]
      (.putNextEntry zos (ZipEntry. "metadata.edn"))
      (.write zos (.getBytes (pr-str {:export_version 1
                                       :exported_at timestamp
                                       :username username}) "UTF-8"))
      (.closeEntry zos)
      (doseq [task (:tasks data)]
        (let [filename (str "tasks/" (:id task) "-" (sanitize-filename (:title task)) ".md")]
          (.putNextEntry zos (ZipEntry. filename))
          (.write zos (.getBytes (task-to-markdown task) "UTF-8"))
          (.closeEntry zos)))
      (.putNextEntry zos (ZipEntry. "people.edn"))
      (.write zos (.getBytes (pr-str (:people data)) "UTF-8"))
      (.closeEntry zos)
      (.putNextEntry zos (ZipEntry. "places.edn"))
      (.write zos (.getBytes (pr-str (:places data)) "UTF-8"))
      (.closeEntry zos)
      (.putNextEntry zos (ZipEntry. "projects.edn"))
      (.write zos (.getBytes (pr-str (:projects data)) "UTF-8"))
      (.closeEntry zos)
      (.putNextEntry zos (ZipEntry. "goals.edn"))
      (.write zos (.getBytes (pr-str (:goals data)) "UTF-8"))
      (.closeEntry zos))
    (.toByteArray baos)))

(defn- require-auth [req]
  (if (prod-mode?)
    (when-let [token (extract-token req)]
      (verify-token token))
    (get-user-from-request req)))

(defn export-data-handler [req]
  (let [user-info (require-auth req)]
    (if-not user-info
      {:status 401 :body {:error "Authentication required"}}
      (try
        (let [user-id (:user-id user-info)
              username (or (:username user-info) "admin")
              data (db/export-all-data (ensure-ds) user-id)
              zip-bytes (create-export-zip username data)
              timestamp (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd-HHmmss"))
              filename (str "export-" (sanitize-filename username) "-" timestamp ".zip")]
          {:status 200
           :headers {"Content-Type" "application/zip"
                     "Content-Disposition" (str "attachment; filename=\"" filename "\"")}
           :body (java.io.ByteArrayInputStream. zip-bytes)})
        (catch Exception e
          {:status 500 :body {:error "Export failed" :message (.getMessage e)}})))))

(defn- serve-index [_]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (slurp (io/resource "public/index.html"))})

(defroutes api-routes
  (context "/api" []
    (GET "/translations" [] translations-handler)
    (GET "/auth/required" [] password-required-handler)
    (GET "/auth/available-users" [] available-users-handler)
    (POST "/auth/login" [] login-handler)
    (PUT "/user/language" [] update-language-handler)
    (GET "/export" [] export-data-handler)
    (GET "/users" [] list-users-handler)
    (POST "/users" [] add-user-handler)
    (DELETE "/users/:id" [] delete-user-handler)
    (GET "/tasks" [] list-tasks-handler)
    (POST "/tasks" [] add-task-handler)
    (PUT "/tasks/:id" [] update-task-handler)
    (DELETE "/tasks/:id" [] delete-task-handler)
    (POST "/tasks/:id/categorize" [] categorize-task-handler)
    (DELETE "/tasks/:id/categorize" [] uncategorize-task-handler)
    (POST "/tasks/:id/reorder" [] reorder-task-handler)
    (PUT "/tasks/:id/due-date" [] set-due-date-handler)
    (PUT "/tasks/:id/due-time" [] set-due-time-handler)
    (PUT "/tasks/:id/done" [] set-task-done-handler)
    (PUT "/tasks/:id/scope" [] set-task-scope-handler)
    (PUT "/tasks/:id/importance" [] set-task-importance-handler)
    (GET "/people" [] list-people-handler)
    (POST "/people" [] add-person-handler)
    (PUT "/people/:id" [] update-person-handler)
    (POST "/people/:id/reorder" [] (fn [req] (reorder-category-handler req db/list-people "people")))
    (GET "/places" [] list-places-handler)
    (POST "/places" [] add-place-handler)
    (PUT "/places/:id" [] update-place-handler)
    (POST "/places/:id/reorder" [] (fn [req] (reorder-category-handler req db/list-places "places")))
    (GET "/projects" [] list-projects-handler)
    (POST "/projects" [] add-project-handler)
    (PUT "/projects/:id" [] update-project-handler)
    (POST "/projects/:id/reorder" [] (fn [req] (reorder-category-handler req db/list-projects "projects")))
    (GET "/goals" [] list-goals-handler)
    (POST "/goals" [] add-goal-handler)
    (PUT "/goals/:id" [] update-goal-handler)
    (POST "/goals/:id/reorder" [] (fn [req] (reorder-category-handler req db/list-goals "goals")))
    (DELETE "/:category/:id" [] delete-category-handler)))

(defroutes app-routes
  api-routes
  (GET "/" [] serve-index)
  (route/resources "/")
  (route/not-found {:status 404 :body {:error "Not found"}}))

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
      (wrap-rate-limit (env-int "RATE_LIMIT_MAX_REQUESTS" (if (prod-mode?) 180 720))
                       (env-int "RATE_LIMIT_WINDOW_SECONDS" 60))))

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
