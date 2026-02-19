(ns et.tr.ui.state.tasks-page
  (:require [clojure.set]
            [et.tr.filters :as filters]
            [et.tr.ui.constants :as constants]))

(defn has-active-filters? [app-state]
  (let [filter-people (:shared/filter-people @app-state)
        filter-places (:shared/filter-places @app-state)
        filter-projects (:shared/filter-projects @app-state)
        filter-goals (:tasks-page/filter-goals @app-state)]
    (or (seq filter-people) (seq filter-places) (seq filter-projects) (seq filter-goals))))

(defn- filter-type->key [filter-type]
  (case filter-type
    constants/CATEGORY-TYPE-PERSON :shared/filter-people
    constants/CATEGORY-TYPE-PLACE :shared/filter-places
    constants/CATEGORY-TYPE-PROJECT :shared/filter-projects
    constants/CATEGORY-TYPE-GOAL :tasks-page/filter-goals))

(defn has-filter-for-type? [app-state filter-type]
  (seq (get @app-state (filter-type->key filter-type))))

(defn- current-fetch-opts [app-state]
  {:search-term (:tasks-page/filter-search @app-state)
   :importance (:tasks-page/importance-filter @app-state)
   :context (:work-private-mode @app-state)
   :strict (:strict-mode @app-state)
   :filter-people (:shared/filter-people @app-state)
   :filter-places (:shared/filter-places @app-state)
   :filter-projects (:shared/filter-projects @app-state)
   :filter-goals (:tasks-page/filter-goals @app-state)})

(defn toggle-filter [app-state fetch-tasks-fn filter-type id]
  (let [filter-key (filter-type->key filter-type)]
    (swap! app-state update filter-key
           #(if (contains? % id)
              (disj % id)
              (conj % id)))
    (fetch-tasks-fn (current-fetch-opts app-state))))

(defn clear-filter [app-state fetch-tasks-fn filter-type]
  (swap! app-state assoc (filter-type->key filter-type) #{})
  (fetch-tasks-fn (current-fetch-opts app-state)))

(defn clear-filter-people [app-state fetch-tasks-fn]
  (clear-filter app-state fetch-tasks-fn constants/CATEGORY-TYPE-PERSON))

(defn clear-filter-places [app-state fetch-tasks-fn]
  (clear-filter app-state fetch-tasks-fn constants/CATEGORY-TYPE-PLACE))

(defn clear-filter-projects [app-state fetch-tasks-fn]
  (clear-filter app-state fetch-tasks-fn constants/CATEGORY-TYPE-PROJECT))

(defn clear-filter-goals [app-state fetch-tasks-fn]
  (clear-filter app-state fetch-tasks-fn constants/CATEGORY-TYPE-GOAL))

(defn set-importance-filter [app-state fetch-tasks-fn level]
  (swap! app-state assoc :tasks-page/importance-filter level)
  (fetch-tasks-fn (assoc (current-fetch-opts app-state) :importance level)))

(defn clear-importance-filter [app-state fetch-tasks-fn]
  (swap! app-state assoc :tasks-page/importance-filter nil)
  (fetch-tasks-fn (assoc (current-fetch-opts app-state) :importance nil)))

(defn clear-uncollapsed-task-filters [app-state fetch-tasks-fn]
  (let [collapsed (:tasks-page/collapsed-filters @app-state)
        all-filters #{:people :places :projects :goals}
        uncollapsed (clojure.set/difference all-filters collapsed)]
    (if (empty? uncollapsed)
      (swap! app-state assoc
             :shared/filter-people #{}
             :shared/filter-places #{}
             :shared/filter-projects #{}
             :tasks-page/filter-goals #{}
             :tasks-page/category-search {:people "" :places "" :projects "" :goals ""}
             :tasks-page/importance-filter nil
             :tasks-page/expanded-task nil)
      (do
        (doseq [filter-key uncollapsed]
          (case filter-key
            :people (swap! app-state assoc :shared/filter-people #{})
            :places (swap! app-state assoc :shared/filter-places #{})
            :projects (swap! app-state assoc :shared/filter-projects #{})
            :goals (swap! app-state assoc :tasks-page/filter-goals #{})))
        (swap! app-state assoc
               :tasks-page/collapsed-filters all-filters
               :tasks-page/category-search {:people "" :places "" :projects "" :goals ""}
               :tasks-page/importance-filter nil
               :tasks-page/expanded-task nil)))
    (fetch-tasks-fn (current-fetch-opts app-state))))

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
  (:tasks @app-state))

(defn has-active-shared-filters? [app-state]
  (let [filter-people (:shared/filter-people @app-state)
        filter-places (:shared/filter-places @app-state)
        filter-projects (:shared/filter-projects @app-state)]
    (or (seq filter-people) (seq filter-places) (seq filter-projects))))

(defn set-pending-new-item [app-state type title on-success & [extra]]
  (let [filter-people (:shared/filter-people @app-state)
        filter-places (:shared/filter-places @app-state)
        filter-projects (:shared/filter-projects @app-state)
        categories (cond-> {:people filter-people
                            :places filter-places
                            :projects filter-projects}
                     (= type :task) (assoc :goals (:tasks-page/filter-goals @app-state)))]
    (swap! app-state assoc :pending-new-item
           (cond-> {:type type
                    :title title
                    :on-success on-success
                    :categories categories}
             extra (merge extra)))))

(defn clear-pending-new-item [app-state]
  (swap! app-state assoc :pending-new-item nil))

(defn update-pending-category [app-state category-type id]
  (let [key (case category-type
              constants/CATEGORY-TYPE-PERSON :people
              constants/CATEGORY-TYPE-PLACE :places
              constants/CATEGORY-TYPE-PROJECT :projects
              constants/CATEGORY-TYPE-GOAL :goals
              (keyword category-type))]
    (swap! app-state update-in [:pending-new-item :categories key]
           #(if (contains? % id) (disj % id) (conj (or % #{}) id)))))

(defn confirm-pending-new-item [app-state dispatch-fns]
  (when-let [{:keys [type title on-success categories] :as item} (:pending-new-item @app-state)]
    (let [add-fn (get dispatch-fns type)
          {:keys [people places projects goals]} categories]
      (case type
        :task (add-fn title categories on-success)
        :resource (add-fn title (:link item) categories on-success)
        :meet (add-fn title categories on-success))
      (when (empty? people) (swap! app-state assoc :shared/filter-people #{}))
      (when (empty? places) (swap! app-state assoc :shared/filter-places #{}))
      (when (empty? projects) (swap! app-state assoc :shared/filter-projects #{}))
      (when (and (= type :task) (empty? goals)) (swap! app-state assoc :tasks-page/filter-goals #{})))
    (clear-pending-new-item app-state)))
