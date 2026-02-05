(ns et.tr.ui.state.ui)

(defn focus-tasks-search []
  (js/setTimeout #(when-let [el (.getElementById js/document "tasks-filter-search")]
                    (.focus el)) 0))

(defn make-tab-initializers [app-state fetch-tasks-fn fetch-messages-fn is-admin-fn]
  {:tasks (fn []
            (swap! app-state assoc :tasks-page/collapsed-filters #{:people :places :projects :goals})
            (let [last-sort-mode (:tasks-page/last-sort-mode @app-state)]
              (swap! app-state assoc :sort-mode last-sort-mode))
            (focus-tasks-search)
            (fetch-tasks-fn {:search-term (:tasks-page/filter-search @app-state)
                             :importance (:tasks-page/importance-filter @app-state)
                             :context (:work-private-mode @app-state)
                             :strict (:strict-mode @app-state)
                             :filter-people (:tasks-page/filter-people @app-state)
                             :filter-places (:tasks-page/filter-places @app-state)
                             :filter-projects (:tasks-page/filter-projects @app-state)
                             :filter-goals (:tasks-page/filter-goals @app-state)}))
   :today (fn []
            (swap! app-state assoc
                   :today-page/collapsed-filters #{:places :projects}
                   :sort-mode :today)
            (fetch-tasks-fn {:context (:work-private-mode @app-state)
                             :strict (:strict-mode @app-state)
                             :excluded-places (:today-page/excluded-places @app-state)
                             :excluded-projects (:today-page/excluded-projects @app-state)}))
   :mail (fn []
           (when (is-admin-fn)
             (fetch-messages-fn)))})

(defn set-active-tab [app-state tab-initializers tab]
  (swap! app-state assoc
         :active-tab tab
         :category-selector/open nil
         :category-selector/search ""
         :tasks-page/category-search {:people "" :places "" :projects "" :goals ""}
         :today-page/category-search {:places "" :projects ""}
         :tasks-page/expanded-task nil
         :today-page/expanded-task nil
         :task-dropdown-open nil)
  (when-let [init-fn (get tab-initializers tab)]
    (init-fn)))

(defn toggle-expanded [app-state page-key task-id]
  (swap! app-state assoc
         page-key (if (= (get @app-state page-key) task-id) nil task-id)
         :category-selector/open nil
         :category-selector/search ""
         :task-dropdown-open nil))

(defn set-editing [app-state task-id]
  (swap! app-state assoc :editing-task task-id))

(defn clear-editing [app-state]
  (swap! app-state assoc :editing-task nil))

(defn- fetch-opts-for-current-tab [app-state context strict]
  (case (:active-tab @app-state)
    :tasks {:search-term (:tasks-page/filter-search @app-state)
            :importance (:tasks-page/importance-filter @app-state)
            :context context
            :strict strict
            :filter-people (:tasks-page/filter-people @app-state)
            :filter-places (:tasks-page/filter-places @app-state)
            :filter-projects (:tasks-page/filter-projects @app-state)
            :filter-goals (:tasks-page/filter-goals @app-state)}
    :today {:context context
            :strict strict
            :excluded-places (:today-page/excluded-places @app-state)
            :excluded-projects (:today-page/excluded-projects @app-state)}
    {:context context :strict strict}))

(defn set-work-private-mode [app-state fetch-tasks-fn mode]
  (swap! app-state assoc :work-private-mode mode)
  (fetch-tasks-fn (fetch-opts-for-current-tab app-state mode (:strict-mode @app-state))))

(defn toggle-strict-mode [app-state fetch-tasks-fn]
  (let [new-strict (not (:strict-mode @app-state))]
    (swap! app-state assoc :strict-mode new-strict)
    (fetch-tasks-fn (fetch-opts-for-current-tab app-state (:work-private-mode @app-state) new-strict))))

(defn toggle-dark-mode [app-state]
  (swap! app-state update :dark-mode not))

(defn setup-dark-mode-watcher [app-state]
  (add-watch app-state :dark-mode-sync
    (fn [_ _ old-state new-state]
      (when (not= (:dark-mode old-state) (:dark-mode new-state))
        (if (:dark-mode new-state)
          (.add (.-classList (.-documentElement js/document)) "dark-mode")
          (.remove (.-classList (.-documentElement js/document)) "dark-mode"))))))

(defn export-data [auth-headers app-state]
  (let [headers (auth-headers)
        url "/api/export"]
    (-> (js/fetch url (clj->js {:method "GET"
                                 :headers headers}))
        (.then (fn [response]
                 (if (.-ok response)
                   (-> (.blob response)
                       (.then (fn [blob]
                                (let [content-disposition (or (.get (.-headers response) "content-disposition") "")
                                      filename (if-let [match (re-find #"filename=\"([^\"]+)\"" content-disposition)]
                                                 (second match)
                                                 "export.zip")
                                      url (js/URL.createObjectURL blob)
                                      a (.createElement js/document "a")]
                                  (set! (.-href a) url)
                                  (set! (.-download a) filename)
                                  (.click a)
                                  (js/URL.revokeObjectURL url)))))
                   (swap! app-state assoc :error "Failed to export data"))))
        (.catch (fn [_]
                  (swap! app-state assoc :error "Failed to export data"))))))
