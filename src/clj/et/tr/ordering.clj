(ns et.tr.ordering
  "Every context in which the user arranges items by hand, and the column that
  context writes.

  A reorder path names a context and looks the column up; no call site spells
  one out. That is the whole point: the day list and Urgent Matters each got
  their order by quietly reusing sort_order, the Tasks and Issues pages' own
  column, so a drag in one moved the other. Adding a context is a line in
  `contexts` here, and et.tr.ordering-isolation-test walks the map and holds
  every pair of contexts apart in both directions — wire a new one to a column
  that is already taken and it fails.

  No context derives a position from another one's column. An item entering a
  context is given a concrete value there and keeps it until it leaves.")

(def contexts
  "Ordering context -> the table and column it owns. `meets`, `meeting_series`,
  `recurring_tasks` and `journals` also carry a sort_order, written once when
  the row is created; nothing reorders them, so they are not contexts."
  {:tasks-page      {:table :tasks            :col :sort_order}
   :tasks-day-list  {:table :tasks            :col :sort_order_today}
   :tasks-urgent    {:table :tasks            :col :sort_order_urgent}
   :issues-page     {:table :issues           :col :sort_order}
   :issues-urgent   {:table :issues           :col :sort_order_urgent}
   :resources-page  {:table :resources        :col :sort_order}
   :journal-entries {:table :journal_entries  :col :sort_order}
   :people          {:table :people           :col :sort_order}
   :places          {:table :places           :col :sort_order}
   :projects        {:table :projects         :col :sort_order}
   :goals           {:table :goals            :col :sort_order}})

(defn- context! [context]
  (or (get contexts context)
      (throw (ex-info "Unknown ordering context"
                      {:context context :known (set (keys contexts))}))))

(defn table [context]
  (:table (context! context)))

(defn column [context]
  (:col (context! context)))

(defn positioning
  "Set-map fragment that gives `context`'s column `value`, for an update that
  writes the position alongside other fields."
  [context value]
  {(column context) value})

(defn clearing
  "Set-map fragment that takes `context`'s position away — for a row leaving the
  context, so nothing is left behind to be read on a later visit."
  [context]
  (positioning context nil))

(defn value-between
  "The position that puts an item `position` (\"before\" or \"after\")
  `target-id` among `ordered`, rows already sorted by `context`'s column:
  halfway to the neighbour on that side, or one step past the edge when there is
  none. nil when `ordered` does not hold the target."
  [context ordered target-id position]
  (let [col (column context)
        rows (vec ordered)
        idx (some (fn [[i row]] (when (= (:id row) target-id) i))
                  (map-indexed vector rows))]
    (when idx
      (let [target-order (or (col (nth rows idx)) 0.0)
            neighbour-idx (if (= position "before") (dec idx) (inc idx))
            neighbour-order (when (and (>= neighbour-idx 0) (< neighbour-idx (count rows)))
                              (or (col (nth rows neighbour-idx)) 0.0))]
        (if neighbour-order
          (/ (+ target-order neighbour-order) 2.0)
          (if (= position "before")
            (- target-order 1.0)
            (+ target-order 1.0)))))))
