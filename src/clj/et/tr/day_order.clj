(ns et.tr.day-order
  "The order of one day's list on the Today page, where meets, tasks due that
  day and tasks merely flagged for it share a single numeric axis. A task the
  user dragged carries an explicit value on that axis (`sort_order_today`);
  everything else derives one from its own time. Deriving never looks at the
  rest of the list, so a stored value keeps meaning as items come and go, and
  meets — whose axis value is their start time — never move relative to each
  other."
  (:require [et.tr.ordering :as ordering]))

(def ^:private minutes-per-day 1440)

(def ^:private untimed-axis
  "Items with no time of day sort before the timed ones, the way the day list's
  date-then-time sort has always had them."
  -1.0)

(def ^:private tail-axis
  "Where a flagged task sits while it carries no position of its own: past every
  minute of the day, so it lands after the timed block. Every join to a day list
  materializes a position, so only a row that predates that can be here — no day
  position is ever derived from another ordering context's column."
  (+ minutes-per-day 1.0))

(defn- minutes-of-day [hhmm]
  (let [[h m] (map #(Integer/parseInt %) (re-seq #"\d+" (str hhmm)))]
    (+ (* 60 (or h 0)) (or m 0))))

(defn- time-axis [hhmm]
  (if (seq hhmm)
    (double (minutes-of-day hhmm))
    untimed-axis))

(defn axis
  "Where `item` sits on its day's axis. `item` is a task or meet tagged with
  :item-type (:task or :meet) and, when the day list carries a task only because
  it is flagged for that day, :day-flagged? true."
  [item]
  (or ((ordering/column :tasks-day-list) item)
      (if (:day-flagged? item)
        tail-axis
        (time-axis (or (:due_time item) (:start_time item))))))

(defn sort-items
  "`items` in display order. The sort is stable, so items that derive the same
  axis value — several untimed ones, two meets at the same time — keep the order
  they came in."
  [items]
  (sort-by axis items))

(defn same-item? [a b]
  (and (= (:item-type a) (:item-type b))
       (= (:id a) (:id b))))

(defn insert-value
  "The axis value that puts a dragged task `position` (\"before\" or \"after\")
  `target` among `items`, or nil if `target` is not one of them. A single value
  cannot land inside a run of items that derive the same one, so a drop against
  such a run goes before or after the whole run."
  [items target position]
  (let [ordered (vec (sort-items items))
        axes (mapv axis ordered)
        idx (some (fn [[i item]] (when (same-item? item target) i))
                  (map-indexed vector ordered))]
    (when idx
      (let [target-axis (nth axes idx)
            step (if (= position "before") -1 1)
            neighbour (->> (iterate #(+ % step) (+ idx step))
                           (take-while #(and (>= % 0) (< % (count axes))))
                           (some #(let [v (nth axes %)] (when (not= v target-axis) v))))]
        (if neighbour
          (/ (+ target-axis neighbour) 2.0)
          (+ target-axis step))))))

(defn end-value
  "The axis value that lands an item after everything `items` holds — the
  position a task takes when it joins the day."
  [items]
  (if (seq items)
    (+ 1.0 (apply max (map axis items)))
    tail-axis))

(defn flagged-date
  "The date whose list `task` is on only because it is marked for that day, or
  nil when no day list holds it for that reason. A day that already holds the
  task because it is due then does not flag it: its position there is its time."
  [task today]
  (cond
    (and (= 1 (:today task))
         (or (nil? (:due_date task)) (pos? (compare (:due_date task) today))))
    today

    (and (:lined_up_for task) (not= (:lined_up_for task) (:due_date task)))
    (:lined_up_for task)))

(defn day-items
  "`date`'s list in display order: the tasks in `tasks` due that day, the meets
  in `meets` happening that day and the tasks merely flagged for it, each tagged
  with :item-type and, for the last group, :day-flagged? true. `tasks` and
  `meets` may span more days than `date`; `today` decides which marker makes a
  task flagged for it."
  [tasks meets today date]
  (let [due (->> tasks
                 (filter #(= (:due_date %) date))
                 (sort-by (juxt #(if (:due_time %) 1 0) :due_time))
                 (map #(assoc % :item-type :task)))
        happening (->> meets
                       (filter #(= (:start_date %) date))
                       (sort-by (juxt #(if (:start_time %) 1 0) :start_time))
                       (map #(assoc % :item-type :meet)))
        flagged (->> tasks
                     (filter #(= date (flagged-date % today)))
                     (sort-by (juxt (ordering/column :tasks-day-list) :id))
                     (map #(assoc % :item-type :task :day-flagged? true)))]
    (sort-items (concat due happening flagged))))
