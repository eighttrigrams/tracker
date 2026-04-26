(ns et.tr.server.today-board-handler
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.tr.db :as db]
            [et.tr.db.task :as db.task]
            [et.tr.db.meet :as db.meet]
            [et.tr.db.journal-entry :as db.journal-entry]
            [et.tr.server.common :as common]))

(defn- today-meets [ds user-id]
  (let [meets (db.meet/list-meets ds user-id {})
        today (:today (jdbc/execute-one! (db/get-conn ds)
                        (sql/format {:select [[[:raw "date('now','localtime')"] :today]]})
                        db/jdbc-opts))]
    (filterv #(= today (:start_date %)) meets)))

(defn today-board-handler
  [req]
  (let [user-id (common/get-user-id req)
        ds (common/ensure-ds)]
    {:status 200
     :body {:tasks (db.task/list-tasks ds user-id :today nil)
            :meets (today-meets ds user-id)
            :journal-entries (db.journal-entry/list-today-journal-entries ds user-id {})}}))
