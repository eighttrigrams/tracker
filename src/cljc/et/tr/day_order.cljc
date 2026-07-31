(ns et.tr.day-order
  "The order of one day's list on the Today page, where meets, tasks due that
  day and tasks merely flagged for it share a single numeric axis. A task the
  user dragged carries an explicit value on that axis (`day_order`); everything
  else derives one from its own time. Deriving never looks at the rest of the
  list, so a stored value keeps meaning as items come and go, and meets — whose
  axis value is their start time — never move relative to each other.")

(def ^:private minutes-per-day 1440)

(def ^:private untimed-axis
  "Items with no time of day sort before the timed ones, the way the day list's
  date-then-time sort has always had them."
  -1.0)

(defn- parse-int [s]
  #?(:clj (Integer/parseInt s)
     :cljs (js/parseInt s 10)))

(defn- minutes-of-day [hhmm]
  (let [[h m] (map parse-int (re-seq #"\d+" (str hhmm)))]
    (+ (* 60 (or h 0)) (or m 0))))

(defn- time-axis [hhmm]
  (if (seq hhmm)
    (double (minutes-of-day hhmm))
    untimed-axis))

(defn- flagged-axis
  "Flagged tasks follow the timed block, keeping the relative order the shared
  sort_order gives them until one of them is dragged. sort_order is unbounded in
  both directions, so it is squashed into (1440,1442) — a band the minutes of a
  day can never reach into."
  [sort-order]
  (let [s (double (or sort-order 0))]
    (+ minutes-per-day 1.0 (/ s (+ 1.0 (Math/abs s))))))

(defn axis
  "Where `item` sits on its day's axis. `item` is a task or meet tagged with
  :item-type (:task or :meet) and, when the day list carries a task only because
  it is flagged for that day, :day-flagged? true."
  [item]
  (or (:day_order item)
      (if (:day-flagged? item)
        (flagged-axis (:sort_order item))
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
