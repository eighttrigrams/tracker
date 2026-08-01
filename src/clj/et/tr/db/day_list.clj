(ns et.tr.db.day-list
  "The day sections of the Today page, built and ordered server-side. One code
  path behind /api/today-board's day window and the day reorder endpoint, so a
  machine caller and the browser read the same order. The reads are deliberately
  lean: only the columns the axis needs, no categories and no relations."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.tr.clock :as clock]
            [et.tr.db :as db]
            [et.tr.day-order :as day-order]))

(def ^:private task-columns [:id :due_date :due_time :today :lined_up_for :sort_order_today])

(def ^:private meet-columns [:id :start_date :start_time])

(defn- window-tasks [conn user-id dates]
  (jdbc/execute! conn
    (sql/format {:select task-columns
                 :from [:tasks]
                 :where [:and (db/user-id-where-clause user-id)
                         [:= :done 0]
                         [:or [:in :due_date dates]
                              [:= :today 1]
                              [:in :lined_up_for dates]]]})
    db/jdbc-opts))

(defn- window-meets [conn user-id dates]
  (jdbc/execute! conn
    (sql/format {:select meet-columns
                 :from [:meets]
                 :where [:and (db/user-id-where-clause user-id)
                         [:= :archived 0]
                         [:in :start_date dates]]})
    db/jdbc-opts))

(defn window
  "The ordered list of each date in `dates`, as [{:date :items}], from one read
  of the constituents rather than one per day."
  [ds user-id dates]
  (let [conn (db/get-conn ds)
        today (clock/today-str)
        tasks (window-tasks conn user-id dates)
        meets (window-meets conn user-id dates)]
    (mapv (fn [date] {:date date :items (day-order/day-items tasks meets today date)}) dates)))

(defn items
  "`date`'s list in display order."
  [ds user-id date]
  (:items (first (window ds user-id [date]))))

(defn end-position
  "The position that lands a task after everything `date`'s list holds."
  [ds user-id date]
  (day-order/end-value (items ds user-id date)))
