(ns et.tr.ui.state.ui)

(defn focus-tasks-search []
  (js/setTimeout #(when-let [el (.getElementById js/document "tasks-filter-search")]
                    (.focus el)) 0))

(defn make-tab-initializers [app-state fetch-tasks-fn fetch-messages-fn is-admin-fn]
  {:tasks (fn []
            (swap! app-state assoc :tasks-page/collapsed-filters #{:people :places :projects :goals})
            (focus-tasks-search)
            (fetch-tasks-fn {:search-term (:tasks-page/filter-search @app-state)}))
   :today (fn []
            (swap! app-state assoc :today-page/collapsed-filters #{:places :projects})
            (when (= :done (:sort-mode @app-state))
              (swap! app-state assoc :sort-mode :manual))
            (fetch-tasks-fn))
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
         :today-page/expanded-task nil)
  (when-let [init-fn (get tab-initializers tab)]
    (init-fn)))

(defn toggle-expanded [app-state page-key task-id]
  (swap! app-state assoc
         page-key (if (= (get @app-state page-key) task-id) nil task-id)
         :category-selector/open nil
         :category-selector/search ""))

(defn set-editing [app-state task-id]
  (swap! app-state assoc :editing-task task-id))

(defn clear-editing [app-state]
  (swap! app-state assoc :editing-task nil))

(defn set-work-private-mode [app-state mode]
  (swap! app-state assoc :work-private-mode mode))

(defn toggle-strict-mode [app-state]
  (swap! app-state update :strict-mode not))

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
