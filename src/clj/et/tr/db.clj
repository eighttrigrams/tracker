(ns et.tr.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [et.tr.migrations :as migrations]
            [buddy.hashers :as hashers]
            [clojure.string]))

(defn init-conn [{:keys [type path]}]
  (let [db-spec (case type
                  :sqlite-memory {:dbtype "sqlite" :dbname "file::memory:?cache=shared"}
                  :sqlite-file {:dbtype "sqlite" :dbname path})
        ds (jdbc/get-datasource db-spec)
        persistent-conn (when (= type :sqlite-memory) (jdbc/get-connection ds))
        conn-for-use (or persistent-conn ds)]
    (migrations/migrate! conn-for-use)
    {:conn conn-for-use
     :persistent-conn persistent-conn
     :type type}))

(defn- get-conn [ds]
  (if (map? ds) (:conn ds) ds))

(defn create-user [ds username password]
  (let [hash (hashers/derive password)]
    (jdbc/execute-one! (get-conn ds)
      ["INSERT INTO users (username, password_hash) VALUES (?, ?) RETURNING id, username, language, created_at"
       username hash]
      {:builder-fn rs/as-unqualified-maps})))

(defn get-user-by-username [ds username]
  (jdbc/execute-one! (get-conn ds)
    ["SELECT id, username, password_hash, language, created_at FROM users WHERE username = ?" username]
    {:builder-fn rs/as-unqualified-maps}))

(defn verify-user [ds username password]
  (when-let [user (get-user-by-username ds username)]
    (when (hashers/check password (:password_hash user))
      (dissoc user :password_hash))))

(defn list-users [ds]
  (jdbc/execute! (get-conn ds)
    ["SELECT id, username, language, created_at FROM users WHERE username != 'admin' ORDER BY created_at"]
    {:builder-fn rs/as-unqualified-maps}))

(defn delete-user [ds user-id]
  (let [conn (get-conn ds)
        task-ids (mapv :id (jdbc/execute! conn
                             ["SELECT id FROM tasks WHERE user_id = ?" user-id]
                             {:builder-fn rs/as-unqualified-maps}))]
    (when (seq task-ids)
      (jdbc/execute-one! conn
        (into [(str "DELETE FROM task_categories WHERE task_id IN ("
                    (clojure.string/join "," (repeat (count task-ids) "?"))
                    ")")] task-ids)))
    (jdbc/execute-one! conn ["DELETE FROM tasks WHERE user_id = ?" user-id])
    (jdbc/execute-one! conn ["DELETE FROM messages WHERE user_id = ?" user-id])
    (jdbc/execute-one! conn ["DELETE FROM people WHERE user_id = ?" user-id])
    (jdbc/execute-one! conn ["DELETE FROM places WHERE user_id = ?" user-id])
    (jdbc/execute-one! conn ["DELETE FROM projects WHERE user_id = ?" user-id])
    (jdbc/execute-one! conn ["DELETE FROM goals WHERE user_id = ?" user-id])
    (let [result (jdbc/execute-one! conn ["DELETE FROM users WHERE id = ?" user-id])]
      {:success (pos? (:next.jdbc/update-count result))})))

(def valid-scopes #{"private" "both" "work"})

(defn- normalize-scope [scope]
  (if (contains? valid-scopes scope) scope "both"))

(def valid-importances #{"normal" "important" "critical"})

(defn- normalize-importance [importance]
  (if (contains? valid-importances importance) importance "normal"))

(def valid-urgencies #{"default" "urgent" "superurgent"})

(defn- normalize-urgency [urgency]
  (if (contains? valid-urgencies urgency) urgency "default"))

(def task-select-columns "id, title, description, created_at, modified_at, due_date, due_time, sort_order, done, scope, importance, urgency")

(defn- user-id-clause [user-id]
  {:clause (if user-id "user_id = ?" "user_id IS NULL")
   :params (if user-id [user-id] [])})

(defn add-task
  ([ds user-id title] (add-task ds user-id title "both"))
  ([ds user-id title scope]
   (let [conn (get-conn ds)
         valid-scope (normalize-scope scope)
         min-order (or (:min_order (jdbc/execute-one! conn
                                     ["SELECT MIN(sort_order) as min_order FROM tasks WHERE user_id = ? OR (user_id IS NULL AND ? IS NULL)"
                                      user-id user-id]
                                     {:builder-fn rs/as-unqualified-maps}))
                       1.0)
         new-order (- min-order 1.0)
         result (jdbc/execute-one! conn
                  [(str "INSERT INTO tasks (title, sort_order, user_id, modified_at, scope) VALUES (?, ?, ?, datetime('now'), ?) RETURNING " task-select-columns ", user_id")
                   title new-order user-id valid-scope]
                  {:builder-fn rs/as-unqualified-maps})]
     result)))

(defn- associate-categories-with-tasks [tasks categories-by-task people-by-id places-by-id projects-by-id goals-by-id]
  (mapv (fn [task]
          (let [task-categories (get categories-by-task (:id task) [])
                task-people (->> task-categories
                                 (filter #(= (:category_type %) "person"))
                                 (keep #(when-let [name (people-by-id (:category_id %))]
                                          {:id (:category_id %) :name name})))
                task-places (->> task-categories
                                 (filter #(= (:category_type %) "place"))
                                 (keep #(when-let [name (places-by-id (:category_id %))]
                                          {:id (:category_id %) :name name})))
                task-projects (->> task-categories
                                   (filter #(= (:category_type %) "project"))
                                   (keep #(when-let [name (projects-by-id (:category_id %))]
                                            {:id (:category_id %) :name name})))
                task-goals (->> task-categories
                                (filter #(= (:category_type %) "goal"))
                                (keep #(when-let [name (goals-by-id (:category_id %))]
                                         {:id (:category_id %) :name name})))]
            (assoc task :people (vec task-people) :places (vec task-places) :projects (vec task-projects) :goals (vec task-goals))))
        tasks))

(defn list-tasks
  ([ds user-id] (list-tasks ds user-id :recent))
  ([ds user-id sort-mode] (list-tasks ds user-id sort-mode nil))
  ([ds user-id sort-mode search-term]
   (let [conn (get-conn ds)
         {:keys [clause params]} (user-id-clause user-id)
         base-where (case sort-mode
                      :due-date (str "WHERE " clause " AND due_date IS NOT NULL AND done = 0")
                      :done (str "WHERE " clause " AND done = 1")
                      (str "WHERE " clause " AND done = 0"))
         search-clause (when (and search-term (not (clojure.string/blank? search-term)))
                         (let [term (clojure.string/lower-case (clojure.string/trim search-term))]
                           {:clause " AND (LOWER(title) LIKE ? OR LOWER(title) LIKE ?)"
                            :params [(str term "%") (str "% " term "%")]}))
         where-clause (str base-where (when search-clause (:clause search-clause)))
         all-params (if search-clause (into params (:params search-clause)) params)
         order-clause (case sort-mode
                        :manual "ORDER BY sort_order ASC, created_at DESC"
                        :due-date "ORDER BY due_date ASC, due_time IS NOT NULL, due_time ASC"
                        :done "ORDER BY modified_at DESC"
                        "ORDER BY modified_at DESC")
         tasks (jdbc/execute! conn
                 (into [(str "SELECT " task-select-columns " FROM tasks " where-clause " " order-clause)] all-params)
                 {:builder-fn rs/as-unqualified-maps})
         task-ids (mapv :id tasks)
         categories (when (seq task-ids)
                      (jdbc/execute! conn
                        (into [(str "SELECT task_id, category_type, category_id FROM task_categories WHERE task_id IN ("
                                    (clojure.string/join "," (repeat (count task-ids) "?"))
                                    ")")] task-ids)
                        {:builder-fn rs/as-unqualified-maps}))
         people (jdbc/execute! conn
                  (into [(str "SELECT id, name FROM people WHERE " clause)] params)
                  {:builder-fn rs/as-unqualified-maps})
         places (jdbc/execute! conn
                  (into [(str "SELECT id, name FROM places WHERE " clause)] params)
                  {:builder-fn rs/as-unqualified-maps})
         projects (jdbc/execute! conn
                    (into [(str "SELECT id, name FROM projects WHERE " clause)] params)
                    {:builder-fn rs/as-unqualified-maps})
         goals (jdbc/execute! conn
                 (into [(str "SELECT id, name FROM goals WHERE " clause)] params)
                 {:builder-fn rs/as-unqualified-maps})
         people-by-id (into {} (map (juxt :id :name) people))
         places-by-id (into {} (map (juxt :id :name) places))
         projects-by-id (into {} (map (juxt :id :name) projects))
         goals-by-id (into {} (map (juxt :id :name) goals))
         categories-by-task (group-by :task_id categories)]
     (associate-categories-with-tasks tasks categories-by-task people-by-id places-by-id projects-by-id goals-by-id))))

(defn add-person [ds user-id name]
  (let [conn (get-conn ds)
        {:keys [clause params]} (user-id-clause user-id)
        max-order (or (:max_order (jdbc/execute-one! conn
                                    (into [(str "SELECT MAX(sort_order) as max_order FROM people WHERE " clause)] params)
                                    {:builder-fn rs/as-unqualified-maps}))
                      0)
        new-order (+ max-order 1.0)]
    (jdbc/execute-one! conn
      ["INSERT INTO people (name, user_id, sort_order) VALUES (?, ?, ?) RETURNING id, name, sort_order" name user-id new-order]
      {:builder-fn rs/as-unqualified-maps})))

(defn add-place [ds user-id name]
  (let [conn (get-conn ds)
        {:keys [clause params]} (user-id-clause user-id)
        max-order (or (:max_order (jdbc/execute-one! conn
                                    (into [(str "SELECT MAX(sort_order) as max_order FROM places WHERE " clause)] params)
                                    {:builder-fn rs/as-unqualified-maps}))
                      0)
        new-order (+ max-order 1.0)]
    (jdbc/execute-one! conn
      ["INSERT INTO places (name, user_id, sort_order) VALUES (?, ?, ?) RETURNING id, name, sort_order" name user-id new-order]
      {:builder-fn rs/as-unqualified-maps})))

(defn list-people [ds user-id]
  (let [{:keys [clause params]} (user-id-clause user-id)]
    (jdbc/execute! (get-conn ds)
      (into [(str "SELECT id, name, description, sort_order FROM people WHERE " clause " ORDER BY sort_order ASC, name ASC")] params)
      {:builder-fn rs/as-unqualified-maps})))

(defn list-places [ds user-id]
  (let [{:keys [clause params]} (user-id-clause user-id)]
    (jdbc/execute! (get-conn ds)
      (into [(str "SELECT id, name, description, sort_order FROM places WHERE " clause " ORDER BY sort_order ASC, name ASC")] params)
      {:builder-fn rs/as-unqualified-maps})))

(defn add-project [ds user-id name]
  (let [conn (get-conn ds)
        {:keys [clause params]} (user-id-clause user-id)
        max-order (or (:max_order (jdbc/execute-one! conn
                                    (into [(str "SELECT MAX(sort_order) as max_order FROM projects WHERE " clause)] params)
                                    {:builder-fn rs/as-unqualified-maps}))
                      0)
        new-order (+ max-order 1.0)]
    (jdbc/execute-one! conn
      ["INSERT INTO projects (name, user_id, sort_order) VALUES (?, ?, ?) RETURNING id, name, sort_order" name user-id new-order]
      {:builder-fn rs/as-unqualified-maps})))

(defn add-goal [ds user-id name]
  (let [conn (get-conn ds)
        {:keys [clause params]} (user-id-clause user-id)
        max-order (or (:max_order (jdbc/execute-one! conn
                                    (into [(str "SELECT MAX(sort_order) as max_order FROM goals WHERE " clause)] params)
                                    {:builder-fn rs/as-unqualified-maps}))
                      0)
        new-order (+ max-order 1.0)]
    (jdbc/execute-one! conn
      ["INSERT INTO goals (name, user_id, sort_order) VALUES (?, ?, ?) RETURNING id, name, sort_order" name user-id new-order]
      {:builder-fn rs/as-unqualified-maps})))

(defn list-projects [ds user-id]
  (let [{:keys [clause params]} (user-id-clause user-id)]
    (jdbc/execute! (get-conn ds)
      (into [(str "SELECT id, name, description, sort_order FROM projects WHERE " clause " ORDER BY sort_order ASC, name ASC")] params)
      {:builder-fn rs/as-unqualified-maps})))

(defn list-goals [ds user-id]
  (let [{:keys [clause params]} (user-id-clause user-id)]
    (jdbc/execute! (get-conn ds)
      (into [(str "SELECT id, name, description, sort_order FROM goals WHERE " clause " ORDER BY sort_order ASC, name ASC")] params)
      {:builder-fn rs/as-unqualified-maps})))

(defn update-person [ds user-id person-id name description]
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [name description person-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE people SET name = ?, description = ? WHERE id = ? AND " clause " RETURNING id, name, description")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn update-place [ds user-id place-id name description]
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [name description place-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE places SET name = ?, description = ? WHERE id = ? AND " clause " RETURNING id, name, description")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn update-project [ds user-id project-id name description]
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [name description project-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE projects SET name = ?, description = ? WHERE id = ? AND " clause " RETURNING id, name, description")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn update-goal [ds user-id goal-id name description]
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [name description goal-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE goals SET name = ?, description = ? WHERE id = ? AND " clause " RETURNING id, name, description")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(def ^:private valid-category-tables #{"people" "places" "projects" "goals"})

(defn- validate-table-name! [table-name]
  (when-not (contains? valid-category-tables table-name)
    (throw (ex-info "Invalid table name" {:table-name table-name}))))

(defn delete-category [ds user-id category-id category-type table-name]
  (validate-table-name! table-name)
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [category-id] params)
        conn (get-conn ds)]
    (jdbc/with-transaction [tx conn]
      (jdbc/execute-one! tx
        (into [(str "DELETE FROM task_categories WHERE category_type = ? AND category_id = ? "
                    "AND task_id IN (SELECT id FROM tasks WHERE " clause ")")
               category-type category-id] params))
      (let [result (jdbc/execute-one! tx
                     (into [(str "DELETE FROM " table-name " WHERE id = ? AND " clause)] query-params))]
        {:success (pos? (:next.jdbc/update-count result))}))))

(defn task-owned-by-user? [ds task-id user-id]
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [task-id] params)]
    (some? (jdbc/execute-one! (get-conn ds)
             (into [(str "SELECT id FROM tasks WHERE id = ? AND " clause)] query-params)
             {:builder-fn rs/as-unqualified-maps}))))

(defn categorize-task [ds user-id task-id category-type category-id]
  (when (task-owned-by-user? ds task-id user-id)
    (let [conn (get-conn ds)]
      (jdbc/execute-one! conn
        ["INSERT OR IGNORE INTO task_categories (task_id, category_type, category_id) VALUES (?, ?, ?)"
         task-id category-type category-id])
      (jdbc/execute-one! conn
        ["UPDATE tasks SET modified_at = datetime('now') WHERE id = ?" task-id]))))

(defn uncategorize-task [ds user-id task-id category-type category-id]
  (when (task-owned-by-user? ds task-id user-id)
    (let [conn (get-conn ds)]
      (jdbc/execute-one! conn
        ["DELETE FROM task_categories WHERE task_id = ? AND category_type = ? AND category_id = ?"
         task-id category-type category-id])
      (jdbc/execute-one! conn
        ["UPDATE tasks SET modified_at = datetime('now') WHERE id = ?" task-id]))))

(defn update-task [ds user-id task-id title description]
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [title description task-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE tasks SET title = ?, description = ?, modified_at = datetime('now') WHERE id = ? AND " clause " RETURNING id, title, description, created_at, modified_at")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn get-task-sort-order [ds user-id task-id]
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [task-id] params)]
    (:sort_order (jdbc/execute-one! (get-conn ds)
                   (into [(str "SELECT sort_order FROM tasks WHERE id = ? AND " clause)] query-params)
                   {:builder-fn rs/as-unqualified-maps}))))

(defn reorder-task [ds user-id task-id new-sort-order]
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [new-sort-order task-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE tasks SET sort_order = ? WHERE id = ? AND " clause)] query-params))
    {:success true :sort_order new-sort-order}))

(defn get-category-sort-order [ds user-id category-id table-name]
  (validate-table-name! table-name)
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [category-id] params)]
    (:sort_order (jdbc/execute-one! (get-conn ds)
                   (into [(str "SELECT sort_order FROM " table-name " WHERE id = ? AND " clause)] query-params)
                   {:builder-fn rs/as-unqualified-maps}))))

(defn reorder-category [ds user-id category-id new-sort-order table-name]
  (validate-table-name! table-name)
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [new-sort-order category-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE " table-name " SET sort_order = ? WHERE id = ? AND " clause)] query-params))
    {:success true :sort_order new-sort-order}))

(defn set-task-due-date [ds user-id task-id due-date]
  (let [{:keys [clause params]} (user-id-clause user-id)
        update-clause (if (nil? due-date)
                        "UPDATE tasks SET due_date = ?, due_time = NULL, modified_at = datetime('now')"
                        "UPDATE tasks SET due_date = ?, modified_at = datetime('now')")
        query-params (concat [due-date task-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str update-clause " WHERE id = ? AND " clause " RETURNING id, due_date, due_time, modified_at")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn set-task-due-time [ds user-id task-id due-time]
  (let [{:keys [clause params]} (user-id-clause user-id)
        normalized-time (if (empty? due-time) nil due-time)
        query-params (concat [normalized-time task-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE tasks SET due_time = ?, modified_at = datetime('now') WHERE id = ? AND " clause " RETURNING id, due_date, due_time, modified_at")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn delete-task [ds user-id task-id]
  (when (task-owned-by-user? ds task-id user-id)
    (jdbc/execute-one! (get-conn ds)
      ["DELETE FROM task_categories WHERE task_id = ?" task-id])
    (let [result (jdbc/execute-one! (get-conn ds)
                   ["DELETE FROM tasks WHERE id = ?" task-id])]
      {:success (pos? (:next.jdbc/update-count result))})))

(defn set-task-done [ds user-id task-id done?]
  (let [{:keys [clause params]} (user-id-clause user-id)
        done-val (if done? 1 0)
        query-params (concat [done-val task-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE tasks SET done = ?, modified_at = datetime('now') WHERE id = ? AND " clause " RETURNING id, done, modified_at")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn set-task-scope [ds user-id task-id scope]
  (let [{:keys [clause params]} (user-id-clause user-id)
        valid-scope (normalize-scope scope)
        query-params (concat [valid-scope task-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE tasks SET scope = ?, modified_at = datetime('now') WHERE id = ? AND " clause " RETURNING id, scope, modified_at")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn set-task-importance [ds user-id task-id importance]
  (let [{:keys [clause params]} (user-id-clause user-id)
        valid-importance (normalize-importance importance)
        query-params (concat [valid-importance task-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE tasks SET importance = ?, modified_at = datetime('now') WHERE id = ? AND " clause " RETURNING id, importance, modified_at")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn set-task-urgency [ds user-id task-id urgency]
  (let [{:keys [clause params]} (user-id-clause user-id)
        valid-urgency (normalize-urgency urgency)
        query-params (concat [valid-urgency task-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE tasks SET urgency = ?, modified_at = datetime('now') WHERE id = ? AND " clause " RETURNING id, urgency, modified_at")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(def valid-languages #{"en" "de" "pt"})

(defn get-user-language [ds user-id]
  (when user-id
    (:language (jdbc/execute-one! (get-conn ds)
                 ["SELECT language FROM users WHERE id = ?" user-id]
                 {:builder-fn rs/as-unqualified-maps}))))

(defn set-user-language [ds user-id language]
  (when (and user-id (contains? valid-languages language))
    (jdbc/execute-one! (get-conn ds)
      ["UPDATE users SET language = ? WHERE id = ? RETURNING id, language" language user-id]
      {:builder-fn rs/as-unqualified-maps})))

(defn- query-categories-chunked [conn task-ids]
  (if (empty? task-ids)
    []
    (let [chunk-size 500
          chunks (partition-all chunk-size task-ids)]
      (mapcat (fn [chunk]
                (jdbc/execute! conn
                  (into [(str "SELECT task_id, category_type, category_id FROM task_categories WHERE task_id IN ("
                              (clojure.string/join "," (repeat (count chunk) "?"))
                              ")")] chunk)
                  {:builder-fn rs/as-unqualified-maps}))
              chunks))))

(defn- normalize-task [task]
  (-> task
      (update :description #(or % ""))
      (update :sort_order #(or % 0.0))
      (update :due_time #(when (and % (not= % "")) %))))

(defn export-all-data [ds user-id]
  (let [conn (get-conn ds)
        {:keys [clause params]} (user-id-clause user-id)
        tasks (jdbc/execute! conn
                (into [(str "SELECT " task-select-columns " FROM tasks WHERE " clause " ORDER BY created_at")] params)
                {:builder-fn rs/as-unqualified-maps})
        task-ids (mapv :id tasks)
        categories (query-categories-chunked conn task-ids)
        people (jdbc/execute! conn
                 (into [(str "SELECT id, name, description, sort_order FROM people WHERE " clause " ORDER BY sort_order ASC, name ASC")] params)
                 {:builder-fn rs/as-unqualified-maps})
        places (jdbc/execute! conn
                 (into [(str "SELECT id, name, description, sort_order FROM places WHERE " clause " ORDER BY sort_order ASC, name ASC")] params)
                 {:builder-fn rs/as-unqualified-maps})
        projects (jdbc/execute! conn
                   (into [(str "SELECT id, name, description, sort_order FROM projects WHERE " clause " ORDER BY sort_order ASC, name ASC")] params)
                   {:builder-fn rs/as-unqualified-maps})
        goals (jdbc/execute! conn
                (into [(str "SELECT id, name, description, sort_order FROM goals WHERE " clause " ORDER BY sort_order ASC, name ASC")] params)
                {:builder-fn rs/as-unqualified-maps})
        people-by-id (into {} (map (juxt :id :name) people))
        places-by-id (into {} (map (juxt :id :name) places))
        projects-by-id (into {} (map (juxt :id :name) projects))
        goals-by-id (into {} (map (juxt :id :name) goals))
        categories-by-task (group-by :task_id categories)
        tasks-with-categories (->> (associate-categories-with-tasks tasks categories-by-task people-by-id places-by-id projects-by-id goals-by-id)
                                   (mapv normalize-task))]
    {:tasks tasks-with-categories
     :people people
     :places places
     :projects projects
     :goals goals}))

(defn add-message [ds user-id sender title description]
  (jdbc/execute-one! (get-conn ds)
    ["INSERT INTO messages (sender, title, description, user_id) VALUES (?, ?, ?, ?) RETURNING id, sender, title, description, created_at, done, user_id"
     sender title (or description "") user-id]
    {:builder-fn rs/as-unqualified-maps}))

(defn list-messages
  ([ds user-id] (list-messages ds user-id :recent))
  ([ds user-id sort-mode]
   (let [{:keys [clause params]} (user-id-clause user-id)
         where-clause (case sort-mode
                        :done (str "WHERE " clause " AND done = 1")
                        (str "WHERE " clause " AND done = 0"))
         order-clause (case sort-mode
                        :done "ORDER BY created_at DESC"
                        "ORDER BY created_at DESC")]
     (jdbc/execute! (get-conn ds)
       (into [(str "SELECT id, sender, title, description, created_at, done FROM messages " where-clause " " order-clause)] params)
       {:builder-fn rs/as-unqualified-maps}))))

(defn message-owned-by-user? [ds message-id user-id]
  (let [{:keys [clause params]} (user-id-clause user-id)
        query-params (concat [message-id] params)]
    (some? (jdbc/execute-one! (get-conn ds)
             (into [(str "SELECT id FROM messages WHERE id = ? AND " clause)] query-params)
             {:builder-fn rs/as-unqualified-maps}))))

(defn set-message-done [ds user-id message-id done?]
  (let [{:keys [clause params]} (user-id-clause user-id)
        done-val (if done? 1 0)
        query-params (concat [done-val message-id] params)]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE messages SET done = ? WHERE id = ? AND " clause " RETURNING id, done")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn delete-message [ds user-id message-id]
  (when (message-owned-by-user? ds message-id user-id)
    (let [result (jdbc/execute-one! (get-conn ds)
                   ["DELETE FROM messages WHERE id = ?" message-id])]
      {:success (pos? (:next.jdbc/update-count result))})))
