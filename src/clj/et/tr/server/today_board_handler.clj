(ns et.tr.server.today-board-handler
  (:require [et.tr.clock :as clock]
            [et.tr.db.task :as db.task]
            [et.tr.db.meet :as db.meet]
            [et.tr.db.journal-entry :as db.journal-entry]
            [et.tr.server.common :as common]))

(defn- board-meets [ds user-id today end]
  (let [meets (db.meet/list-meets ds user-id {})]
    (filterv (fn [m]
               (let [d (:start_date m)]
                 (and (some? d)
                      (<= 0 (compare d today))
                      (<= 0 (compare end d)))))
             meets)))

(defn today-board-handler
  "GET /api/today-board — fetch the board for the current user: tasks due/marked
  for today, meets within the date window, and today's journal entries. Optional
  ?days=N (non-negative integer, default 0) widens the meets window to
  today..today+N; days absent or 0 means today only (unchanged behavior).
  Returns {:tasks :meets :journal-entries}."
  [req]
  (let [user-id (common/get-user-id req)
        ds (common/ensure-ds)
        days (max 0 (or (common/parse-int-opt (get-in req [:params "days"])) 0))
        today (clock/today-str)
        end (str (.plusDays (java.time.LocalDate/parse today) days))]
    {:status 200
     :body {:tasks (db.task/list-tasks ds user-id :today nil)
            :meets (board-meets ds user-id today end)
            :journal-entries (db.journal-entry/list-today-journal-entries ds user-id {})}}))
