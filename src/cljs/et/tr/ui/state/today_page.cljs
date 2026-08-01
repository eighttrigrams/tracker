(ns et.tr.ui.state.today-page
  (:require [clojure.set]
            [et.tr.ui.date :as date]))

(def ^:private today-str date/today-str)
(def ^:private horizon-order date/horizon-order)
(def ^:private horizon-end-date date/horizon-end-date)

(declare selected-day-date)

(def ^:const target-upcoming-tasks-count 10)

(defn- count-upcoming-items-for-horizon [app-state horizon]
  (let [after-date (selected-day-date app-state)
        end-date (horizon-end-date horizon)
        task-count (count (filter (fn [task]
                                    (and (:due_date task)
                                         (> (:due_date task) after-date)
                                         (<= (:due_date task) end-date)))
                                  (:tasks @app-state)))
        meet-count (count (filter (fn [meet]
                                    (and (:start_date meet)
                                         (> (:start_date meet) after-date)
                                         (<= (:start_date meet) end-date)))
                                  (:today-meets @app-state)))]
    (+ task-count meet-count)))

(defn calculate-best-horizon [app-state]
  (or (first (filter #(>= (count-upcoming-items-for-horizon app-state %) target-upcoming-tasks-count) horizon-order))
      :eighteen-months))

(defn set-upcoming-horizon [app-state horizon]
  (swap! app-state assoc :upcoming-horizon horizon))

(defn current-fetch-opts [app-state]
  {:context (:work-private-mode @app-state)
   :strict (:strict-mode @app-state)
   :filter-people (:shared/filter-people @app-state)
   :filter-places (:shared/filter-places @app-state)
   :filter-projects (:shared/filter-projects @app-state)
   :filter-goals (:shared/filter-goals @app-state)})

(def ^:private all-filter-keys #{:people :places :projects :goals})

(defn clear-uncollapsed-today-filters [app-state fetch-fn]
  (let [collapsed (:today-page/collapsed-filters @app-state)
        any-visible? (seq (clojure.set/difference all-filter-keys collapsed))]
    (when-not any-visible?
      (swap! app-state assoc
             :shared/filter-people #{}
             :shared/filter-places #{}
             :shared/filter-projects #{}
             :shared/filter-goals #{}
             :today-page/category-search {:people "" :places "" :projects "" :goals ""})
      (.scrollTo js/window 0 0)
      (fetch-fn (current-fetch-opts app-state)))))

(defn toggle-today-filter-collapsed [app-state filter-key]
  (let [was-collapsed (contains? (:today-page/collapsed-filters @app-state) filter-key)]
    (swap! app-state update :today-page/collapsed-filters
           (fn [collapsed]
             (if (contains? collapsed filter-key)
               (disj all-filter-keys filter-key)
               (conj collapsed filter-key))))
    (when was-collapsed
      (swap! app-state update :today-page/category-search
             (fn [searches]
               (reduce #(assoc %1 %2 "") searches all-filter-keys)))
      (js/setTimeout
       (fn []
         (when-let [el (.getElementById js/document (str "today-filter-" (name filter-key)))]
           (.focus el)))
       0))))

(defn set-today-category-search [app-state category-key search-term]
  (swap! app-state assoc-in [:today-page/category-search category-key] search-term))

(defn set-today-selected-view [app-state view]
  (when (#{:urgent :upcoming :reminders} view)
    (swap! app-state assoc :today-page/selected-view view)))

(defn set-selected-day [app-state day-offset]
  (swap! app-state assoc :today-page/selected-day day-offset)
  (swap! app-state assoc :upcoming-horizon (calculate-best-horizon app-state)))

(defn selected-day-date [app-state]
  (let [offset (or (:today-page/selected-day @app-state) 0)]
    (date/add-days (today-str) offset)))

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
  (let [after-date (selected-day-date app-state)
        horizon (:upcoming-horizon @app-state)
        end-date (horizon-end-date horizon)]
    (->> (:tasks @app-state)
         (remove task-done?)
         (filter #(and (:due_date %)
                       (> (:due_date %) after-date)
                       (<= (:due_date %) end-date)))
         (sort-by-date-and-time))))

(defn- tasks-by-urgency [app-state urgency-level]
  (let [today (today-str)
        sel-offset (or (:today-page/selected-day @app-state) 0)
        sel-date (date/add-days today sel-offset)]
    (->> (:tasks @app-state)
         (filter #(= urgency-level (:urgency %)))
         (remove #(= (:due_date %) today))
         (remove #(and (:due_date %) (< (:due_date %) today)))
         (remove #(and (zero? sel-offset) (= 1 (:today %))))
         (remove #(= (:lined_up_for %) sel-date))
         (sort-by :sort_order))))

(defn reminder-tasks [app-state]
  (->> (:tasks @app-state)
       (filter #(= "active" (:reminder %)))
       (sort-by :modified_at)))

(defn superurgent-tasks [app-state]
  (tasks-by-urgency app-state "superurgent"))

(defn urgent-tasks [app-state]
  (tasks-by-urgency app-state "urgent"))

(defn- issues-by-urgency [app-state urgency-level]
  (->> (:issues @app-state)
       (filter #(= urgency-level (:urgency %)))
       (sort-by :sort_order)))

(defn superurgent-issues [app-state]
  (issues-by-urgency app-state "superurgent"))

(defn urgent-issues [app-state]
  (issues-by-urgency app-state "urgent"))

(defn- sort-meets-by-date-and-time [meets]
  (sort-by (juxt :start_date #(if (:start_time %) 1 0) :start_time) meets))

(defn today-meets [app-state]
  (let [today (today-str)]
    (->> (:today-meets @app-state)
         (filter #(= (:start_date %) today))
         sort-meets-by-date-and-time)))

(defn set-day-lists
  "Store the backend's order for the day window, keyed by date."
  [app-state days]
  (swap! app-state assoc :today-page/day-lists
         (into {} (map (juxt :date #(vec (:items %)))) days)))

(defn- same-day-ref? [a b]
  (and (= (:type a) (:type b)) (= (:id a) (:id b))))

(defn splice-day-item
  "Move `item` next to `target` in `date`'s list right away, so a dropped card
  does not sit at its old place until the backend's order comes back. A plain
  vector move — the value it will get is the server's to compute."
  [app-state date item target position]
  (swap! app-state update-in [:today-page/day-lists date]
         (fn [items]
           (let [held (or (first (filter #(same-day-ref? % item) items)) item)
                 without (vec (remove #(same-day-ref? % item) items))
                 idx (some (fn [[i x]] (when (same-day-ref? x target) i))
                           (map-indexed vector without))]
             (if idx
               (let [at (if (= position "before") idx (inc idx))]
                 (into (conj (subvec without 0 at) held) (subvec without at)))
               items)))))

(defn selected-day-items
  "The selected day's list as the backend ordered it, resolved against the rows
  the page already holds. Anything the active filters kept out simply has no row
  to resolve to, which leaves the order of the rest untouched."
  [app-state]
  (let [state @app-state
        tasks-by-id (into {} (map (juxt :id identity)) (:tasks state))
        meets-by-id (into {} (map (juxt :id identity)) (:today-meets state))]
    (keep (fn [{:keys [type id flagged]}]
            (if (= "meet" type)
              (some-> (meets-by-id id) (assoc :item-type :meet))
              (some-> (tasks-by-id id) (assoc :item-type :task :day-flagged? (boolean flagged)))))
          (get (:today-page/day-lists state) (selected-day-date app-state)))))

(defn upcoming-meets [app-state]
  (let [after-date (selected-day-date app-state)
        horizon (:upcoming-horizon @app-state)
        end-date (horizon-end-date horizon)]
    (->> (:today-meets @app-state)
         (filter #(and (> (:start_date %) after-date)
                       (<= (:start_date %) end-date)))
         sort-meets-by-date-and-time)))
