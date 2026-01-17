(ns et.tr.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [et.tr.migrations :as migrations]
            [buddy.hashers :as hashers]))

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
      ["INSERT INTO users (username, password_hash) VALUES (?, ?) RETURNING id, username, created_at"
       username hash]
      {:builder-fn rs/as-unqualified-maps})))

(defn get-user-by-username [ds username]
  (jdbc/execute-one! (get-conn ds)
    ["SELECT id, username, password_hash, created_at FROM users WHERE username = ?" username]
    {:builder-fn rs/as-unqualified-maps}))

(defn verify-user [ds username password]
  (when-let [user (get-user-by-username ds username)]
    (when (hashers/check password (:password_hash user))
      (dissoc user :password_hash))))

(defn list-users [ds]
  (jdbc/execute! (get-conn ds)
    ["SELECT id, username, created_at FROM users WHERE username != 'admin' ORDER BY created_at"]
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
    (jdbc/execute-one! conn ["DELETE FROM people WHERE user_id = ?" user-id])
    (jdbc/execute-one! conn ["DELETE FROM places WHERE user_id = ?" user-id])
    (jdbc/execute-one! conn ["DELETE FROM projects WHERE user_id = ?" user-id])
    (jdbc/execute-one! conn ["DELETE FROM goals WHERE user_id = ?" user-id])
    (let [result (jdbc/execute-one! conn ["DELETE FROM users WHERE id = ?" user-id])]
      {:success (pos? (:next.jdbc/update-count result))})))

(defn add-task [ds user-id title]
  (let [conn (get-conn ds)
        min-order (or (:min_order (jdbc/execute-one! conn
                                    ["SELECT MIN(sort_order) as min_order FROM tasks WHERE user_id = ? OR (user_id IS NULL AND ? IS NULL)"
                                     user-id user-id]
                                    {:builder-fn rs/as-unqualified-maps}))
                      1.0)
        new-order (- min-order 1.0)
        result (jdbc/execute-one! conn
                 ["INSERT INTO tasks (title, sort_order, user_id) VALUES (?, ?, ?) RETURNING id, title, description, created_at, sort_order, user_id"
                  title new-order user-id]
                 {:builder-fn rs/as-unqualified-maps})]
    result))

(defn list-tasks
  ([ds user-id] (list-tasks ds user-id :recent))
  ([ds user-id sort-mode]
   (let [conn (get-conn ds)
         user-filter (if user-id
                       "user_id = ?"
                       "user_id IS NULL")
         where-clause (if (= sort-mode :due-date)
                        (str "WHERE " user-filter " AND due_date IS NOT NULL")
                        (str "WHERE " user-filter))
         order-clause (case sort-mode
                        :manual "ORDER BY sort_order ASC, created_at DESC"
                        :due-date "ORDER BY due_date ASC"
                        "ORDER BY created_at DESC")
         query-params (if user-id [user-id] [])
         tasks (jdbc/execute! conn
                 (into [(str "SELECT id, title, description, created_at, due_date, sort_order FROM tasks " where-clause " " order-clause)] query-params)
                 {:builder-fn rs/as-unqualified-maps})
         task-ids (mapv :id tasks)
         categories (when (seq task-ids)
                      (jdbc/execute! conn
                        (into [(str "SELECT task_id, category_type, category_id FROM task_categories WHERE task_id IN ("
                                    (clojure.string/join "," (repeat (count task-ids) "?"))
                                    ")")] task-ids)
                        {:builder-fn rs/as-unqualified-maps}))
         people (jdbc/execute! conn
                  (into [(str "SELECT id, name FROM people WHERE " user-filter)] query-params)
                  {:builder-fn rs/as-unqualified-maps})
         places (jdbc/execute! conn
                  (into [(str "SELECT id, name FROM places WHERE " user-filter)] query-params)
                  {:builder-fn rs/as-unqualified-maps})
         projects (jdbc/execute! conn
                    (into [(str "SELECT id, name FROM projects WHERE " user-filter)] query-params)
                    {:builder-fn rs/as-unqualified-maps})
         goals (jdbc/execute! conn
                 (into [(str "SELECT id, name FROM goals WHERE " user-filter)] query-params)
                 {:builder-fn rs/as-unqualified-maps})
         people-by-id (into {} (map (juxt :id :name) people))
         places-by-id (into {} (map (juxt :id :name) places))
         projects-by-id (into {} (map (juxt :id :name) projects))
         goals-by-id (into {} (map (juxt :id :name) goals))
         categories-by-task (group-by :task_id categories)]
     (mapv (fn [task]
             (let [task-categories (get categories-by-task (:id task) [])
                   task-people (->> task-categories
                                    (filter #(= (:category_type %) "person"))
                                    (mapv #(hash-map :id (:category_id %) :name (people-by-id (:category_id %)))))
                   task-places (->> task-categories
                                    (filter #(= (:category_type %) "place"))
                                    (mapv #(hash-map :id (:category_id %) :name (places-by-id (:category_id %)))))
                   task-projects (->> task-categories
                                      (filter #(= (:category_type %) "project"))
                                      (mapv #(hash-map :id (:category_id %) :name (projects-by-id (:category_id %)))))
                   task-goals (->> task-categories
                                   (filter #(= (:category_type %) "goal"))
                                   (mapv #(hash-map :id (:category_id %) :name (goals-by-id (:category_id %)))))]
               (assoc task :people task-people :places task-places :projects task-projects :goals task-goals)))
           tasks))))

(defn add-person [ds user-id name]
  (jdbc/execute-one! (get-conn ds)
    ["INSERT INTO people (name, user_id) VALUES (?, ?) RETURNING id, name" name user-id]
    {:builder-fn rs/as-unqualified-maps}))

(defn add-place [ds user-id name]
  (jdbc/execute-one! (get-conn ds)
    ["INSERT INTO places (name, user_id) VALUES (?, ?) RETURNING id, name" name user-id]
    {:builder-fn rs/as-unqualified-maps}))

(defn list-people [ds user-id]
  (let [user-filter (if user-id "user_id = ?" "user_id IS NULL")
        query-params (if user-id [user-id] [])]
    (jdbc/execute! (get-conn ds)
      (into [(str "SELECT id, name FROM people WHERE " user-filter " ORDER BY name")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn list-places [ds user-id]
  (let [user-filter (if user-id "user_id = ?" "user_id IS NULL")
        query-params (if user-id [user-id] [])]
    (jdbc/execute! (get-conn ds)
      (into [(str "SELECT id, name FROM places WHERE " user-filter " ORDER BY name")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn add-project [ds user-id name]
  (jdbc/execute-one! (get-conn ds)
    ["INSERT INTO projects (name, user_id) VALUES (?, ?) RETURNING id, name" name user-id]
    {:builder-fn rs/as-unqualified-maps}))

(defn add-goal [ds user-id name]
  (jdbc/execute-one! (get-conn ds)
    ["INSERT INTO goals (name, user_id) VALUES (?, ?) RETURNING id, name" name user-id]
    {:builder-fn rs/as-unqualified-maps}))

(defn list-projects [ds user-id]
  (let [user-filter (if user-id "user_id = ?" "user_id IS NULL")
        query-params (if user-id [user-id] [])]
    (jdbc/execute! (get-conn ds)
      (into [(str "SELECT id, name FROM projects WHERE " user-filter " ORDER BY name")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn list-goals [ds user-id]
  (let [user-filter (if user-id "user_id = ?" "user_id IS NULL")
        query-params (if user-id [user-id] [])]
    (jdbc/execute! (get-conn ds)
      (into [(str "SELECT id, name FROM goals WHERE " user-filter " ORDER BY name")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn task-owned-by-user? [ds task-id user-id]
  (let [user-filter (if user-id "user_id = ?" "user_id IS NULL")
        query-params (if user-id [task-id user-id] [task-id])]
    (some? (jdbc/execute-one! (get-conn ds)
             (into [(str "SELECT id FROM tasks WHERE id = ? AND " user-filter)] query-params)
             {:builder-fn rs/as-unqualified-maps}))))

(defn categorize-task [ds user-id task-id category-type category-id]
  (when (task-owned-by-user? ds task-id user-id)
    (jdbc/execute-one! (get-conn ds)
      ["INSERT OR IGNORE INTO task_categories (task_id, category_type, category_id) VALUES (?, ?, ?)"
       task-id category-type category-id])))

(defn uncategorize-task [ds user-id task-id category-type category-id]
  (when (task-owned-by-user? ds task-id user-id)
    (jdbc/execute-one! (get-conn ds)
      ["DELETE FROM task_categories WHERE task_id = ? AND category_type = ? AND category_id = ?"
       task-id category-type category-id])))

(defn update-task [ds user-id task-id title description]
  (let [user-filter (if user-id "user_id = ?" "user_id IS NULL")
        query-params (if user-id [title description task-id user-id] [title description task-id])]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE tasks SET title = ?, description = ? WHERE id = ? AND " user-filter " RETURNING id, title, description, created_at")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn get-task-sort-order [ds user-id task-id]
  (let [user-filter (if user-id "user_id = ?" "user_id IS NULL")
        query-params (if user-id [task-id user-id] [task-id])]
    (:sort_order (jdbc/execute-one! (get-conn ds)
                   (into [(str "SELECT sort_order FROM tasks WHERE id = ? AND " user-filter)] query-params)
                   {:builder-fn rs/as-unqualified-maps}))))

(defn reorder-task [ds user-id task-id new-sort-order]
  (let [user-filter (if user-id "user_id = ?" "user_id IS NULL")
        query-params (if user-id [new-sort-order task-id user-id] [new-sort-order task-id])]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE tasks SET sort_order = ? WHERE id = ? AND " user-filter)] query-params))
    {:success true :sort_order new-sort-order}))

(defn set-task-due-date [ds user-id task-id due-date]
  (let [user-filter (if user-id "user_id = ?" "user_id IS NULL")
        query-params (if user-id [due-date task-id user-id] [due-date task-id])]
    (jdbc/execute-one! (get-conn ds)
      (into [(str "UPDATE tasks SET due_date = ? WHERE id = ? AND " user-filter " RETURNING id, due_date")] query-params)
      {:builder-fn rs/as-unqualified-maps})))

(defn delete-task [ds user-id task-id]
  (when (task-owned-by-user? ds task-id user-id)
    (jdbc/execute-one! (get-conn ds)
      ["DELETE FROM task_categories WHERE task_id = ?" task-id])
    (let [result (jdbc/execute-one! (get-conn ds)
                   ["DELETE FROM tasks WHERE id = ?" task-id])]
      {:success (pos? (:next.jdbc/update-count result))})))
