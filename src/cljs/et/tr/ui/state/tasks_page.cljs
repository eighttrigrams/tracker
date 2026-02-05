(ns et.tr.ui.state.tasks-page
  (:require [clojure.set]
            [et.tr.filters :as filters]))

(def ^:const CATEGORY-TYPE-PERSON "person")
(def ^:const CATEGORY-TYPE-PLACE "place")
(def ^:const CATEGORY-TYPE-PROJECT "project")
(def ^:const CATEGORY-TYPE-GOAL "goal")

(defn has-active-filters? [app-state]
  (let [filter-people (:tasks-page/filter-people @app-state)
        filter-places (:tasks-page/filter-places @app-state)
        filter-projects (:tasks-page/filter-projects @app-state)
        filter-goals (:tasks-page/filter-goals @app-state)]
    (or (seq filter-people) (seq filter-places) (seq filter-projects) (seq filter-goals))))

(defn- filter-type->key [filter-type]
  (case filter-type
    CATEGORY-TYPE-PERSON :tasks-page/filter-people
    CATEGORY-TYPE-PLACE :tasks-page/filter-places
    CATEGORY-TYPE-PROJECT :tasks-page/filter-projects
    CATEGORY-TYPE-GOAL :tasks-page/filter-goals))

(defn toggle-filter [app-state filter-type id]
  (let [filter-key (filter-type->key filter-type)]
    (swap! app-state update filter-key
           #(if (contains? % id)
              (disj % id)
              (conj % id)))))

(defn clear-filter [app-state filter-type]
  (swap! app-state assoc (filter-type->key filter-type) #{}))

(defn clear-filter-people [app-state]
  (clear-filter app-state CATEGORY-TYPE-PERSON))

(defn clear-filter-places [app-state]
  (clear-filter app-state CATEGORY-TYPE-PLACE))

(defn clear-filter-projects [app-state]
  (clear-filter app-state CATEGORY-TYPE-PROJECT))

(defn clear-filter-goals [app-state]
  (clear-filter app-state CATEGORY-TYPE-GOAL))

(defn- current-fetch-opts [app-state]
  {:search-term (:tasks-page/filter-search @app-state)
   :importance (:tasks-page/importance-filter @app-state)
   :context (:work-private-mode @app-state)
   :strict (:strict-mode @app-state)})

(defn set-importance-filter [app-state fetch-tasks-fn level]
  (swap! app-state assoc :tasks-page/importance-filter level)
  (fetch-tasks-fn (assoc (current-fetch-opts app-state) :importance level)))

(defn clear-importance-filter [app-state fetch-tasks-fn]
  (swap! app-state assoc :tasks-page/importance-filter nil)
  (fetch-tasks-fn (assoc (current-fetch-opts app-state) :importance nil)))

(defn clear-uncollapsed-task-filters [app-state]
  (let [collapsed (:tasks-page/collapsed-filters @app-state)
        all-filters #{:people :places :projects :goals}
        uncollapsed (clojure.set/difference all-filters collapsed)]
    (if (empty? uncollapsed)
      (swap! app-state assoc
             :tasks-page/filter-people #{}
             :tasks-page/filter-places #{}
             :tasks-page/filter-projects #{}
             :tasks-page/filter-goals #{}
             :tasks-page/category-search {:people "" :places "" :projects "" :goals ""}
             :tasks-page/importance-filter nil
             :tasks-page/expanded-task nil)
      (do
        (doseq [filter-key uncollapsed]
          (case filter-key
            :people (swap! app-state assoc :tasks-page/filter-people #{})
            :places (swap! app-state assoc :tasks-page/filter-places #{})
            :projects (swap! app-state assoc :tasks-page/filter-projects #{})
            :goals (swap! app-state assoc :tasks-page/filter-goals #{})))
        (swap! app-state assoc
               :tasks-page/collapsed-filters all-filters
               :tasks-page/category-search {:people "" :places "" :projects "" :goals ""}
               :tasks-page/importance-filter nil
               :tasks-page/expanded-task nil)))))

(defn toggle-filter-collapsed [app-state filter-key]
  (let [was-collapsed (contains? (:tasks-page/collapsed-filters @app-state) filter-key)
        all-filters #{:people :places :projects :goals}]
    (swap! app-state update :tasks-page/collapsed-filters
           (fn [collapsed]
             (if (contains? collapsed filter-key)
               (disj all-filters filter-key)
               (conj collapsed filter-key))))
    (when was-collapsed
      (swap! app-state update :tasks-page/category-search
             (fn [searches]
               (reduce #(assoc %1 %2 "") searches all-filters))))
    (js/setTimeout
     (fn []
       (when-let [el (.getElementById js/document
                                      (if was-collapsed
                                        (str "tasks-filter-" (name filter-key))
                                        "tasks-search"))]
         (.focus el)))
     0)))

(defonce search-debounce-timer (atom nil))

(defn set-filter-search [app-state fetch-tasks-fn search-term]
  (swap! app-state assoc :tasks-page/filter-search search-term)
  (when-let [timer @search-debounce-timer]
    (js/clearTimeout timer))
  (reset! search-debounce-timer
          (js/setTimeout #(fetch-tasks-fn (assoc (current-fetch-opts app-state) :search-term search-term)) 300)))

(defn set-category-search [app-state category-key search-term]
  (swap! app-state assoc-in [:tasks-page/category-search category-key] search-term))

(defn open-category-selector [app-state selector-id]
  (swap! app-state assoc
         :category-selector/open selector-id
         :category-selector/search ""))

(defn close-category-selector [app-state]
  (swap! app-state assoc
         :category-selector/open nil
         :category-selector/search ""))

(defn set-category-selector-search [app-state search-term]
  (swap! app-state assoc :category-selector/search search-term))

(defn focus-tasks-search []
  (js/setTimeout #(when-let [el (.getElementById js/document "tasks-filter-search")]
                    (.focus el)) 0))

(defn prefix-matches? [title search-term]
  (filters/multi-prefix-matches? title search-term))

(defn filtered-tasks [app-state]
  (let [tasks (:tasks @app-state)
        filter-people (:tasks-page/filter-people @app-state)
        filter-places (:tasks-page/filter-places @app-state)
        filter-projects (:tasks-page/filter-projects @app-state)
        filter-goals (:tasks-page/filter-goals @app-state)
        matches-any? (fn [task-categories filter-ids]
                       (some #(contains? filter-ids (:id %)) task-categories))]
    (cond->> tasks
      (seq filter-people) (filter #(matches-any? (:people %) filter-people))
      (seq filter-places) (filter #(matches-any? (:places %) filter-places))
      (seq filter-projects) (filter #(matches-any? (:projects %) filter-projects))
      (seq filter-goals) (filter #(matches-any? (:goals %) filter-goals)))))

(defn set-pending-new-task [app-state title on-success]
  (let [filter-people (:tasks-page/filter-people @app-state)
        filter-places (:tasks-page/filter-places @app-state)
        filter-projects (:tasks-page/filter-projects @app-state)
        filter-goals (:tasks-page/filter-goals @app-state)]
    (swap! app-state assoc :pending-new-task
           {:title title
            :on-success on-success
            :categories {:people filter-people
                         :places filter-places
                         :projects filter-projects
                         :goals filter-goals}})))

(defn clear-pending-new-task [app-state]
  (swap! app-state assoc :pending-new-task nil))

(defn update-pending-category [app-state category-type id]
  (let [key (case category-type
              CATEGORY-TYPE-PERSON :people
              CATEGORY-TYPE-PLACE :places
              CATEGORY-TYPE-PROJECT :projects
              CATEGORY-TYPE-GOAL :goals
              (keyword category-type))]
    (swap! app-state update-in [:pending-new-task :categories key]
           #(if (contains? % id) (disj % id) (conj (or % #{}) id)))))

(defn confirm-pending-new-task [app-state add-task-with-categories-fn]
  (when-let [{:keys [title on-success categories]} (:pending-new-task @app-state)]
    (add-task-with-categories-fn title categories on-success)
    (clear-pending-new-task app-state)))
