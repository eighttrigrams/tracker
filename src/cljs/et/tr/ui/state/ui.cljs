(ns et.tr.ui.state.ui
  (:require [et.tr.ui.state.mail :as mail-state]))

(defn focus-tasks-search []
  (js/setTimeout #(when-let [el (.getElementById js/document "tasks-filter-search")]
                    (.focus el)) 0))

(defn- tasks-fetch-opts
  ([app-state]
   (tasks-fetch-opts app-state (:work-private-mode @app-state) (:strict-mode @app-state)))
  ([app-state context strict]
   {:search-term (:tasks-page/filter-search @app-state)
    :importance (:tasks-page/importance-filter @app-state)
    :context context
    :strict strict
    :filter-people (:tasks-page/filter-people @app-state)
    :filter-places (:tasks-page/filter-places @app-state)
    :filter-projects (:tasks-page/filter-projects @app-state)
    :filter-goals (:tasks-page/filter-goals @app-state)}))

(defn- today-fetch-opts
  ([app-state]
   (today-fetch-opts app-state (:work-private-mode @app-state) (:strict-mode @app-state)))
  ([app-state context strict]
   {:context context
    :strict strict
    :excluded-places (:today-page/excluded-places @app-state)
    :excluded-projects (:today-page/excluded-projects @app-state)}))

(defn- initialize-tasks-page [app-state fetch-tasks-fn]
  (swap! app-state assoc :tasks-page/collapsed-filters #{:people :places :projects :goals})
  (let [last-sort-mode (:tasks-page/last-sort-mode @app-state)]
    (swap! app-state assoc :sort-mode last-sort-mode))
  (focus-tasks-search)
  (fetch-tasks-fn (tasks-fetch-opts app-state)))

(defn make-tab-initializers [app-state {:keys [fetch-tasks fetch-messages is-admin]}]
  {:tasks (fn []
            (initialize-tasks-page app-state fetch-tasks))
   :today (fn []
            (swap! app-state assoc
                   :today-page/collapsed-filters #{:places :projects}
                   :sort-mode :today)
            (fetch-tasks (today-fetch-opts app-state)))
   :mail (fn []
           (when (is-admin)
             (fetch-messages)))})

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
  (mail-state/reset-mail-page-view-state!)
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
    :tasks (tasks-fetch-opts app-state context strict)
    :today (today-fetch-opts app-state context strict)
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

(defn- extract-filename [response]
  (let [content-disposition (or (.get (.-headers response) "content-disposition") "")]
    (if-let [match (re-find #"filename=\"([^\"]+)\"" content-disposition)]
      (second match)
      "export.zip")))

(defn- trigger-download [blob filename]
  (let [url (js/URL.createObjectURL blob)
        a (.createElement js/document "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (.click a)
    (js/URL.revokeObjectURL url)))

(defn- handle-export-response [response app-state]
  (if (.-ok response)
    (.then (.blob response)
           (fn [blob]
             (trigger-download blob (extract-filename response))))
    (swap! app-state assoc :error "Failed to export data")))

(defn export-data [auth-headers app-state]
  (let [headers (auth-headers)
        url "/api/export"]
    (-> (js/fetch url (clj->js {:method "GET"
                                 :headers headers}))
        (.then #(handle-export-response % app-state))
        (.catch (fn [_]
                  (swap! app-state assoc :error "Failed to export data"))))))
