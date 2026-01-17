(ns et.tr.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn init-conn [{:keys [type path]}]
  (let [db-spec (case type
                  :sqlite-memory {:dbtype "sqlite" :dbname "file::memory:?cache=shared"}
                  :sqlite-file {:dbtype "sqlite" :dbname path})]
    (jdbc/get-datasource db-spec)))

(defn create-tables [ds]
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS tasks (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       title TEXT NOT NULL,
                       description TEXT DEFAULT '',
                       created_at TEXT NOT NULL DEFAULT (datetime('now')))"])
  (try
    (jdbc/execute! ds ["ALTER TABLE tasks ADD COLUMN description TEXT DEFAULT ''"])
    (catch Exception _))
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS people (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       name TEXT NOT NULL UNIQUE)"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS places (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       name TEXT NOT NULL UNIQUE)"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS task_categories (
                       task_id INTEGER NOT NULL,
                       category_type TEXT NOT NULL,
                       category_id INTEGER NOT NULL,
                       PRIMARY KEY (task_id, category_type, category_id),
                       FOREIGN KEY (task_id) REFERENCES tasks(id))"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS projects (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       name TEXT NOT NULL UNIQUE)"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS goals (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       name TEXT NOT NULL UNIQUE)"])
  (try
    (jdbc/execute! ds ["ALTER TABLE tasks ADD COLUMN sort_order REAL DEFAULT NULL"])
    (catch Exception _))
  (jdbc/execute! ds ["UPDATE tasks SET sort_order = (SELECT COUNT(*) FROM tasks t2 WHERE t2.created_at >= tasks.created_at) WHERE sort_order IS NULL"]))

(defn add-task [ds title]
  (let [min-order (or (:min_order (jdbc/execute-one! ds
                                    ["SELECT MIN(sort_order) as min_order FROM tasks"]
                                    {:builder-fn rs/as-unqualified-maps}))
                      1.0)
        new-order (- min-order 1.0)
        result (jdbc/execute-one! ds
                 ["INSERT INTO tasks (title, sort_order) VALUES (?, ?) RETURNING id, title, description, created_at, sort_order"
                  title new-order]
                 {:builder-fn rs/as-unqualified-maps})]
    result))

(defn list-tasks
  ([ds] (list-tasks ds :recent))
  ([ds sort-mode]
   (let [order-clause (if (= sort-mode :manual)
                        "ORDER BY sort_order ASC, created_at DESC"
                        "ORDER BY created_at DESC")
         tasks (jdbc/execute! ds
                 [(str "SELECT id, title, description, created_at, sort_order FROM tasks " order-clause)]
                 {:builder-fn rs/as-unqualified-maps})
         categories (jdbc/execute! ds
                      ["SELECT task_id, category_type, category_id FROM task_categories"]
                      {:builder-fn rs/as-unqualified-maps})
         people (jdbc/execute! ds
                  ["SELECT id, name FROM people"]
                  {:builder-fn rs/as-unqualified-maps})
         places (jdbc/execute! ds
                  ["SELECT id, name FROM places"]
                  {:builder-fn rs/as-unqualified-maps})
         projects (jdbc/execute! ds
                    ["SELECT id, name FROM projects"]
                    {:builder-fn rs/as-unqualified-maps})
         goals (jdbc/execute! ds
                 ["SELECT id, name FROM goals"]
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

(defn add-person [ds name]
  (jdbc/execute-one! ds
    ["INSERT INTO people (name) VALUES (?) RETURNING id, name" name]
    {:builder-fn rs/as-unqualified-maps}))

(defn add-place [ds name]
  (jdbc/execute-one! ds
    ["INSERT INTO places (name) VALUES (?) RETURNING id, name" name]
    {:builder-fn rs/as-unqualified-maps}))

(defn list-people [ds]
  (jdbc/execute! ds
    ["SELECT id, name FROM people ORDER BY name"]
    {:builder-fn rs/as-unqualified-maps}))

(defn list-places [ds]
  (jdbc/execute! ds
    ["SELECT id, name FROM places ORDER BY name"]
    {:builder-fn rs/as-unqualified-maps}))

(defn add-project [ds name]
  (jdbc/execute-one! ds
    ["INSERT INTO projects (name) VALUES (?) RETURNING id, name" name]
    {:builder-fn rs/as-unqualified-maps}))

(defn add-goal [ds name]
  (jdbc/execute-one! ds
    ["INSERT INTO goals (name) VALUES (?) RETURNING id, name" name]
    {:builder-fn rs/as-unqualified-maps}))

(defn list-projects [ds]
  (jdbc/execute! ds
    ["SELECT id, name FROM projects ORDER BY name"]
    {:builder-fn rs/as-unqualified-maps}))

(defn list-goals [ds]
  (jdbc/execute! ds
    ["SELECT id, name FROM goals ORDER BY name"]
    {:builder-fn rs/as-unqualified-maps}))

(defn categorize-task [ds task-id category-type category-id]
  (jdbc/execute-one! ds
    ["INSERT OR IGNORE INTO task_categories (task_id, category_type, category_id) VALUES (?, ?, ?)"
     task-id category-type category-id]))

(defn uncategorize-task [ds task-id category-type category-id]
  (jdbc/execute-one! ds
    ["DELETE FROM task_categories WHERE task_id = ? AND category_type = ? AND category_id = ?"
     task-id category-type category-id]))

(defn update-task [ds task-id title description]
  (jdbc/execute-one! ds
    ["UPDATE tasks SET title = ?, description = ? WHERE id = ? RETURNING id, title, description, created_at"
     title description task-id]
    {:builder-fn rs/as-unqualified-maps}))

(defn get-task-sort-order [ds task-id]
  (:sort_order (jdbc/execute-one! ds
                 ["SELECT sort_order FROM tasks WHERE id = ?" task-id]
                 {:builder-fn rs/as-unqualified-maps})))

(defn reorder-task [ds task-id new-sort-order]
  (jdbc/execute-one! ds
    ["UPDATE tasks SET sort_order = ? WHERE id = ?" new-sort-order task-id])
  {:success true :sort_order new-sort-order})
