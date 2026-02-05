(ns et.tr.ui.state.today-page
  (:require [clojure.set]
            [et.tr.ui.date :as date]))

(def ^:private today-str date/today-str)
(def ^:private horizon-order date/horizon-order)
(def ^:private horizon-end-date date/horizon-end-date)

(def ^:const target-upcoming-tasks-count 10)

(defn count-upcoming-tasks-for-horizon [tasks horizon]
  (let [today (today-str)
        end-date (horizon-end-date horizon)]
    (count (filter (fn [task]
                     (and (:due_date task)
                          (> (:due_date task) today)
                          (<= (:due_date task) end-date)))
                   tasks))))

(defn calculate-best-horizon [tasks]
  (or (first (filter #(>= (count-upcoming-tasks-for-horizon tasks %) target-upcoming-tasks-count) horizon-order))
      :eighteen-months))

(defn set-upcoming-horizon [app-state horizon]
  (swap! app-state assoc :upcoming-horizon horizon))

(defn- current-fetch-opts [app-state]
  {:context (:work-private-mode @app-state)
   :strict (:strict-mode @app-state)
   :excluded-places (:today-page/excluded-places @app-state)
   :excluded-projects (:today-page/excluded-projects @app-state)})

(defn toggle-today-excluded-place [app-state fetch-tasks-fn place-id]
  (swap! app-state update :today-page/excluded-places
         #(if (contains? % place-id)
            (disj % place-id)
            (conj % place-id)))
  (fetch-tasks-fn (current-fetch-opts app-state)))

(defn toggle-today-excluded-project [app-state fetch-tasks-fn project-id]
  (swap! app-state update :today-page/excluded-projects
         #(if (contains? % project-id)
            (disj % project-id)
            (conj % project-id)))
  (fetch-tasks-fn (current-fetch-opts app-state)))

(defn clear-today-excluded-places [app-state fetch-tasks-fn]
  (swap! app-state assoc :today-page/excluded-places #{})
  (fetch-tasks-fn (current-fetch-opts app-state)))

(defn clear-today-excluded-projects [app-state fetch-tasks-fn]
  (swap! app-state assoc :today-page/excluded-projects #{})
  (fetch-tasks-fn (current-fetch-opts app-state)))

(defn clear-uncollapsed-today-filters [app-state fetch-tasks-fn]
  (let [collapsed (:today-page/collapsed-filters @app-state)
        all-filters #{:places :projects}
        uncollapsed (clojure.set/difference all-filters collapsed)]
    (if (empty? uncollapsed)
      (swap! app-state assoc
             :today-page/excluded-places #{}
             :today-page/excluded-projects #{}
             :today-page/category-search {:places "" :projects ""})
      (do
        (doseq [filter-key uncollapsed]
          (case filter-key
            :places (swap! app-state assoc :today-page/excluded-places #{})
            :projects (swap! app-state assoc :today-page/excluded-projects #{})))
        (swap! app-state assoc
               :today-page/collapsed-filters all-filters
               :today-page/category-search {:places "" :projects ""})))
    (fetch-tasks-fn (current-fetch-opts app-state))))

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
         (sort-by-date-and-time))))

(defn today-tasks [app-state]
  (let [today (today-str)]
    (->> (:tasks @app-state)
         (filter #(= (:due_date %) today))
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
         (sort-by-date-and-time))))

(defn superurgent-tasks [app-state]
  (->> (:tasks @app-state)
       (filter #(= "superurgent" (:urgency %)))
       (sort-by :sort_order)))

(defn urgent-tasks [app-state]
  (->> (:tasks @app-state)
       (filter #(= "urgent" (:urgency %)))
       (sort-by :sort_order)))
