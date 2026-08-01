(ns et.tr.server.today-board-handler
  (:require [et.tr.clock :as clock]
            [et.tr.db.task :as db.task]
            [et.tr.db.meet :as db.meet]
            [et.tr.db.day-list :as db.day-list]
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

(defn- window-dates [today days]
  (let [start (java.time.LocalDate/parse today)]
    (mapv #(str (.plusDays start %)) (range (inc days)))))

(defn- day-refs
  "One day's order as references into :tasks and :meets, rather than a second
  copy of rows the same response already carries."
  [items]
  (mapv (fn [item]
          {:type (name (:item-type item))
           :id (:id item)
           :flagged (boolean (:day-flagged? item))})
        items))

(defn today-board-handler
  "GET /api/today-board — fetch the board for the current user: tasks due/marked
  for today, meets within the date window, today's journal entries, and the
  order of each day's list. Optional ?days=N (non-negative integer, default 0)
  widens the window to today..today+N; days absent or 0 means today only.
  Returns {:tasks :meets :journal-entries :days}, where :days is
  [{:date :items}] and each item is {:type \"task\"|\"meet\" :id :flagged} —
  the manual order the Today page shows, resolved against :tasks and :meets."
  [req]
  (let [user-id (common/get-user-id req)
        ds (common/ensure-ds)
        days (max 0 (or (common/parse-int-opt (get-in req [:params "days"])) 0))
        today (clock/today-str)
        dates (window-dates today days)
        end (last dates)]
    {:status 200
     :body {:tasks (db.task/list-tasks ds user-id :today nil)
            :meets (board-meets ds user-id today end)
            :journal-entries (db.journal-entry/list-today-journal-entries ds user-id {})
            :days (mapv #(update % :items day-refs) (db.day-list/window ds user-id dates))}}))
