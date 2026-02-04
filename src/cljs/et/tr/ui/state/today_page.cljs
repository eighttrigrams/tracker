(ns et.tr.ui.state.today-page
  (:require [clojure.set]
            [et.tr.filters :as filters]
            [et.tr.ui.date :as date]))

(defn- apply-today-exclusion-filter [app-state tasks]
  (filters/apply-exclusion-filter tasks
                                  (:today-page/excluded-places @app-state)
                                  (:today-page/excluded-projects @app-state)))

(def today-str date/today-str)
(def add-days date/add-days)
(def day-of-week date/day-of-week)
(def format-date-with-day date/format-date-with-day)
(def get-day-name date/get-day-name)
(def within-days? date/within-days?)
(def today-formatted date/today-formatted)
(def horizon-order date/horizon-order)
(def horizon-end-date date/horizon-end-date)

(defn count-upcoming-tasks-for-horizon [tasks horizon]
  (let [today (today-str)
        end-date (horizon-end-date horizon)]
    (count (filter (fn [task]
                     (and (:due_date task)
                          (> (:due_date task) today)
                          (<= (:due_date task) end-date)))
                   tasks))))

(defn calculate-best-horizon [tasks]
  (or (first (filter #(>= (count-upcoming-tasks-for-horizon tasks %) filters/target-upcoming-tasks-count) horizon-order))
      :eighteen-months))

(defn- recalculate-today-horizon [app-state]
  (let [tasks (:tasks @app-state)
        filtered-tasks (apply-today-exclusion-filter app-state tasks)]
    (swap! app-state assoc :upcoming-horizon (calculate-best-horizon filtered-tasks))))

(defn set-upcoming-horizon [app-state horizon]
  (swap! app-state assoc :upcoming-horizon horizon))

(defn toggle-today-excluded-place [app-state place-id]
  (swap! app-state update :today-page/excluded-places
         #(if (contains? % place-id)
            (disj % place-id)
            (conj % place-id)))
  (recalculate-today-horizon app-state))

(defn toggle-today-excluded-project [app-state project-id]
  (swap! app-state update :today-page/excluded-projects
         #(if (contains? % project-id)
            (disj % project-id)
            (conj % project-id)))
  (recalculate-today-horizon app-state))

(defn clear-today-excluded-places [app-state]
  (swap! app-state assoc :today-page/excluded-places #{})
  (recalculate-today-horizon app-state))

(defn clear-today-excluded-projects [app-state]
  (swap! app-state assoc :today-page/excluded-projects #{})
  (recalculate-today-horizon app-state))

(defn clear-uncollapsed-today-filters [app-state]
  (let [collapsed (:today-page/collapsed-filters @app-state)
        all-filters #{:places :projects}
        uncollapsed (clojure.set/difference all-filters collapsed)]
    (if (empty? uncollapsed)
      (do
        (swap! app-state assoc
               :today-page/excluded-places #{}
               :today-page/excluded-projects #{}
               :today-page/category-search {:places "" :projects ""})
        (recalculate-today-horizon app-state))
      (do
        (doseq [filter-key uncollapsed]
          (case filter-key
            :places (swap! app-state assoc :today-page/excluded-places #{})
            :projects (swap! app-state assoc :today-page/excluded-projects #{})))
        (swap! app-state assoc
               :today-page/collapsed-filters all-filters
               :today-page/category-search {:places "" :projects ""})
        (recalculate-today-horizon app-state)))))

(defn toggle-today-filter-collapsed [app-state filter-key]
  (let [was-collapsed (contains? (:today-page/collapsed-filters @app-state) filter-key)
        all-filters #{:places :projects}]
    (swap! app-state update :today-page/collapsed-filters
           (fn [collapsed]
             (if (contains? collapsed filter-key)
               (disj all-filters filter-key)
               (conj collapsed filter-key))))
    (when was-collapsed
      (swap! app-state update :today-page/category-search
             (fn [searches]
               (reduce #(assoc %1 %2 "") searches all-filters)))
      (js/setTimeout
       (fn []
         (when-let [el (.getElementById js/document (str "today-filter-" (name filter-key)))]
           (.focus el)))
       0))))

(defn set-today-category-search [app-state category-key search-term]
  (swap! app-state assoc-in [:today-page/category-search category-key] search-term))

(defn set-today-selected-view [app-state view]
  (when (#{:urgent :upcoming} view)
    (swap! app-state assoc :today-page/selected-view view)))

(defn- sort-by-date-and-time [tasks]
  (sort-by (juxt :due_date #(if (:due_time %) 1 0) :due_time) tasks))

(defn task-done? [task]
  (= 1 (:done task)))

(defn overdue-tasks [app-state]
  (let [today (today-str)]
    (->> (:tasks @app-state)
         (filter #(and (:due_date %)
                       (< (:due_date %) today)))
         (apply-today-exclusion-filter app-state)
         (sort-by-date-and-time))))

(defn today-tasks [app-state]
  (let [today (today-str)]
    (->> (:tasks @app-state)
         (filter #(= (:due_date %) today))
         (apply-today-exclusion-filter app-state)
         (sort-by-date-and-time))))

(defn upcoming-tasks [app-state]
  (let [today (today-str)
        horizon (:upcoming-horizon @app-state)
        end-date (horizon-end-date horizon)]
    (->> (:tasks @app-state)
         (remove task-done?)
         (filter #(and (:due_date %)
                       (> (:due_date %) today)
                       (<= (:due_date %) end-date)))
         (apply-today-exclusion-filter app-state)
         (sort-by-date-and-time))))

(defn superurgent-tasks [app-state]
  (->> (:tasks @app-state)
       (filter #(= "superurgent" (:urgency %)))
       (apply-today-exclusion-filter app-state)
       (sort-by :sort_order)))

(defn urgent-tasks [app-state]
  (->> (:tasks @app-state)
       (filter #(= "urgent" (:urgency %)))
       (apply-today-exclusion-filter app-state)
       (sort-by :sort_order)))
