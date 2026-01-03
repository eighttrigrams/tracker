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
                       created_at TEXT NOT NULL DEFAULT (datetime('now')))"]))

(defn add-item [ds title]
  (let [result (jdbc/execute-one! ds
                ["INSERT INTO items (title) VALUES (?) RETURNING id, title, created_at" title]
                {:builder-fn rs/as-unqualified-maps})]
    result))

(defn list-items [ds]
  (jdbc/execute! ds
    ["SELECT id, title, created_at FROM items ORDER BY created_at DESC"]
    {:builder-fn rs/as-unqualified-maps}))
