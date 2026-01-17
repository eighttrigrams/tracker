(ns et.tr.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [et.tr.migrations :as migrations]))

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

(defn add-task [ds title]
  (let [conn (get-conn ds)
        min-order (or (:min_order (jdbc/execute-one! conn
                                    ["SELECT MIN(sort_order) as min_order FROM tasks"]
                                    {:builder-fn rs/as-unqualified-maps}))
                      1.0)
        new-order (- min-order 1.0)
        result (jdbc/execute-one! conn
                 ["INSERT INTO tasks (title, sort_order) VALUES (?, ?) RETURNING id, title, description, created_at, sort_order"
                  title new-order]
                 {:builder-fn rs/as-unqualified-maps})]
    result))

(defn list-tasks
  ([ds] (list-tasks ds :recent))
  ([ds sort-mode]
   (let [conn (get-conn ds)
         where-clause (if (= sort-mode :due-date)
                          "WHERE due_date IS NOT NULL"
                          "")
         order-clause (case sort-mode
                        :manual "ORDER BY sort_order ASC, created_at DESC"
                        :due-date "ORDER BY due_date ASC"
                        "ORDER BY created_at DESC")
         tasks (jdbc/execute! conn
                 [(str "SELECT id, title, description, created_at, due_date, sort_order FROM tasks " where-clause " " order-clause)]
                 {:builder-fn rs/as-unqualified-maps})
         categories (jdbc/execute! conn
                      ["SELECT task_id, category_type, category_id FROM task_categories"]
                      {:builder-fn rs/as-unqualified-maps})
         people (jdbc/execute! conn
                  ["SELECT id, name FROM people"]
                  {:builder-fn rs/as-unqualified-maps})
         places (jdbc/execute! conn
                  ["SELECT id, name FROM places"]
                  {:builder-fn rs/as-unqualified-maps})
         projects (jdbc/execute! conn
                    ["SELECT id, name FROM projects"]
                    {:builder-fn rs/as-unqualified-maps})
         goals (jdbc/execute! conn
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
  (jdbc/execute-one! (get-conn ds)
    ["INSERT INTO people (name) VALUES (?) RETURNING id, name" name]
    {:builder-fn rs/as-unqualified-maps}))

(defn add-place [ds name]
  (jdbc/execute-one! (get-conn ds)
    ["INSERT INTO places (name) VALUES (?) RETURNING id, name" name]
    {:builder-fn rs/as-unqualified-maps}))

(defn list-people [ds]
  (jdbc/execute! (get-conn ds)
    ["SELECT id, name FROM people ORDER BY name"]
    {:builder-fn rs/as-unqualified-maps}))

(defn list-places [ds]
  (jdbc/execute! (get-conn ds)
    ["SELECT id, name FROM places ORDER BY name"]
    {:builder-fn rs/as-unqualified-maps}))

(defn add-project [ds name]
  (jdbc/execute-one! (get-conn ds)
    ["INSERT INTO projects (name) VALUES (?) RETURNING id, name" name]
    {:builder-fn rs/as-unqualified-maps}))

(defn add-goal [ds name]
  (jdbc/execute-one! (get-conn ds)
    ["INSERT INTO goals (name) VALUES (?) RETURNING id, name" name]
    {:builder-fn rs/as-unqualified-maps}))

(defn list-projects [ds]
  (jdbc/execute! (get-conn ds)
    ["SELECT id, name FROM projects ORDER BY name"]
    {:builder-fn rs/as-unqualified-maps}))

(defn list-goals [ds]
  (jdbc/execute! (get-conn ds)
    ["SELECT id, name FROM goals ORDER BY name"]
    {:builder-fn rs/as-unqualified-maps}))

(defn categorize-task [ds task-id category-type category-id]
  (jdbc/execute-one! (get-conn ds)
    ["INSERT OR IGNORE INTO task_categories (task_id, category_type, category_id) VALUES (?, ?, ?)"
     task-id category-type category-id]))

(defn uncategorize-task [ds task-id category-type category-id]
  (jdbc/execute-one! (get-conn ds)
    ["DELETE FROM task_categories WHERE task_id = ? AND category_type = ? AND category_id = ?"
     task-id category-type category-id]))

(defn update-task [ds task-id title description]
  (jdbc/execute-one! (get-conn ds)
    ["UPDATE tasks SET title = ?, description = ? WHERE id = ? RETURNING id, title, description, created_at"
     title description task-id]
    {:builder-fn rs/as-unqualified-maps}))

(defn get-task-sort-order [ds task-id]
  (:sort_order (jdbc/execute-one! (get-conn ds)
                 ["SELECT sort_order FROM tasks WHERE id = ?" task-id]
                 {:builder-fn rs/as-unqualified-maps})))

(defn reorder-task [ds task-id new-sort-order]
  (jdbc/execute-one! (get-conn ds)
    ["UPDATE tasks SET sort_order = ? WHERE id = ?" new-sort-order task-id])
  {:success true :sort_order new-sort-order})

(defn set-task-due-date [ds task-id due-date]
  (jdbc/execute-one! (get-conn ds)
    ["UPDATE tasks SET due_date = ? WHERE id = ? RETURNING id, due_date"
     due-date task-id]
    {:builder-fn rs/as-unqualified-maps}))
