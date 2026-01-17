(ns et.tr.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn init-conn [{:keys [type path]}]
  (let [db-spec (case type
                  :sqlite-memory {:dbtype "sqlite" :dbname "file::memory:?cache=shared"}
                  :sqlite-file {:dbtype "sqlite" :dbname path})]
    (jdbc/get-datasource db-spec)))

(defn create-tables [ds]
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS items (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       title TEXT NOT NULL,
                       description TEXT DEFAULT '',
                       created_at TEXT NOT NULL DEFAULT (datetime('now')))"])
  (try
    (jdbc/execute! ds ["ALTER TABLE items ADD COLUMN description TEXT DEFAULT ''"])
    (catch Exception _))
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS people (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       name TEXT NOT NULL UNIQUE)"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS places (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       name TEXT NOT NULL UNIQUE)"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS item_tags (
                       item_id INTEGER NOT NULL,
                       tag_type TEXT NOT NULL,
                       tag_id INTEGER NOT NULL,
                       PRIMARY KEY (item_id, tag_type, tag_id),
                       FOREIGN KEY (item_id) REFERENCES items(id))"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS projects (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       name TEXT NOT NULL UNIQUE)"])
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS goals (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       name TEXT NOT NULL UNIQUE)"])
  (try
    (jdbc/execute! ds ["ALTER TABLE items ADD COLUMN sort_order REAL DEFAULT NULL"])
    (catch Exception _))
  (jdbc/execute! ds ["UPDATE items SET sort_order = (SELECT COUNT(*) FROM items i2 WHERE i2.created_at >= items.created_at) WHERE sort_order IS NULL"]))

(defn add-item [ds title]
  (let [min-order (or (:min_order (jdbc/execute-one! ds
                                    ["SELECT MIN(sort_order) as min_order FROM items"]
                                    {:builder-fn rs/as-unqualified-maps}))
                      1.0)
        new-order (- min-order 1.0)
        result (jdbc/execute-one! ds
                 ["INSERT INTO items (title, sort_order) VALUES (?, ?) RETURNING id, title, description, created_at, sort_order"
                  title new-order]
                 {:builder-fn rs/as-unqualified-maps})]
    result))

(defn list-items
  ([ds] (list-items ds :recent))
  ([ds sort-mode]
   (let [order-clause (if (= sort-mode :manual)
                        "ORDER BY sort_order ASC, created_at DESC"
                        "ORDER BY created_at DESC")
         items (jdbc/execute! ds
                 [(str "SELECT id, title, description, created_at, sort_order FROM items " order-clause)]
                 {:builder-fn rs/as-unqualified-maps})
        tags (jdbc/execute! ds
               ["SELECT item_id, tag_type, tag_id FROM item_tags"]
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
        tags-by-item (group-by :item_id tags)]
    (mapv (fn [item]
            (let [item-tags (get tags-by-item (:id item) [])
                  item-people (->> item-tags
                                   (filter #(= (:tag_type %) "person"))
                                   (mapv #(hash-map :id (:tag_id %) :name (people-by-id (:tag_id %)))))
                  item-places (->> item-tags
                                   (filter #(= (:tag_type %) "place"))
                                   (mapv #(hash-map :id (:tag_id %) :name (places-by-id (:tag_id %)))))
                  item-projects (->> item-tags
                                     (filter #(= (:tag_type %) "project"))
                                     (mapv #(hash-map :id (:tag_id %) :name (projects-by-id (:tag_id %)))))
                  item-goals (->> item-tags
                                  (filter #(= (:tag_type %) "goal"))
                                  (mapv #(hash-map :id (:tag_id %) :name (goals-by-id (:tag_id %)))))]
              (assoc item :people item-people :places item-places :projects item-projects :goals item-goals)))
          items))))

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

(defn tag-item [ds item-id tag-type tag-id]
  (jdbc/execute-one! ds
    ["INSERT OR IGNORE INTO item_tags (item_id, tag_type, tag_id) VALUES (?, ?, ?)"
     item-id tag-type tag-id]))

(defn untag-item [ds item-id tag-type tag-id]
  (jdbc/execute-one! ds
    ["DELETE FROM item_tags WHERE item_id = ? AND tag_type = ? AND tag_id = ?"
     item-id tag-type tag-id]))

(defn update-item [ds item-id title description]
  (jdbc/execute-one! ds
    ["UPDATE items SET title = ?, description = ? WHERE id = ? RETURNING id, title, description, created_at"
     title description item-id]
    {:builder-fn rs/as-unqualified-maps}))

(defn get-item-sort-order [ds item-id]
  (:sort_order (jdbc/execute-one! ds
                 ["SELECT sort_order FROM items WHERE id = ?" item-id]
                 {:builder-fn rs/as-unqualified-maps})))

(defn reorder-item [ds item-id new-sort-order]
  (jdbc/execute-one! ds
    ["UPDATE items SET sort_order = ? WHERE id = ?" new-sort-order item-id])
  {:success true :sort_order new-sort-order})
