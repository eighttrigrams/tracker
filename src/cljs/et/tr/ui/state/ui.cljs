(ns et.tr.ui.state.ui
  (:require [et.tr.ui.constants :as constants]
            [et.tr.ui.state.category-filters :as category-filters]
            [et.tr.ui.state.mail :as mail-state]
            [et.tr.ui.state.resources :as resources-state]
            [et.tr.ui.state.issues :as issues-state]
            [et.tr.ui.state.meets :as meets-state]
            [et.tr.ui.state.reports :as reports-state]
            [et.tr.ui.state.relations :as relations-state]))

;; **Three of these ids sit on two elements each, and that is checked, not an
;; oversight.** `tasks-filter-search`, `meets-filter-search` and
;; `resources-filter-search` are each written by two different search-add-forms
;; — a page's own and its sub-mode's — but the two are arms of the *same* `cond`,
;; so only one of a pair is ever mounted:
;;
;;   tasks-filter-search      core.cljs         recurring-mode → recurring-search-add-form
;;                                              :else          → combined-search-add-form
;;   meets-filter-search      views/meets       series-mode    → series-search-add-form
;;                                              :else          → search-add-form
;;   resources-filter-search  views/resources   journals-mode  → journal-search-add-form
;;                                              :else          → search-add-form
;;
;; Each of those conds has a middle arm too — recurring-filter, series-filter,
;; journal-filter — and all three render a filter-bar, which carries no search
;; box at all. So `getElementById` cannot return the wrong element: there is
;; never a second one for it to choose between.
;;
;; Written down because the obvious reading of a grep is that the ids need
;; renaming. They do not, and it would cost twice: every lookup here would have
;; to learn which sub-mode is on to know what to ask for, and the e2e steps
;; (`test/e2e/steps/*.ts`) address the boxes by exactly these ids.
(defn focus-input! [id]
  (js/setTimeout #(when-let [el (.getElementById js/document id)]
                    (.focus el #js {:preventScroll true})) 0))

(defn focus-tasks-search []
  (focus-input! "tasks-filter-search"))

(def ^:private page-search-prefixes
  "The tabs whose search box is named after them. The id convention is
  `<page-prefix>-filter-search`, and the prefix is the same `:page-prefix` the
  views hand components/filter-section; a tab that is not here falls back to
  \"tasks\", exactly as that component's `(or page-prefix \"tasks\")` does."
  #{:today :issues :meets :resources :reports})

(defn focus-page-search
  "Put the cursor in the search box of the page on screen.

  The prefix comes from `:active-tab` rather than from a prop threaded down to
  the card: a card is only ever rendered on the page that is active, and reading
  the tab is one function against a prop that would have to pass through
  item-card and every view that configures it. Not `focus-tasks-search`, which is
  the Tasks-only shortcut and would put the cursor in the Tasks box while the
  Issues page is on screen. Focusing a page that has no search box (Today) is a
  no-op, since focus-input! only acts on an element that exists."
  [app-state]
  (let [tab (:active-tab @app-state)]
    (focus-input! (str (if (contains? page-search-prefixes tab) (name tab) "tasks")
                       "-filter-search"))))

(defn- tasks-fetch-opts
  ([app-state]
   (tasks-fetch-opts app-state (:work-private-mode @app-state) (:strict-mode @app-state)))
  ([app-state context strict]
   (cond-> (merge (category-filters/fetch-opts app-state)
                  {:search-term (:tasks-page/filter-search @app-state)
                   :importance (:tasks-page/importance-filter @app-state)
                   :context context
                   :strict strict})
     (:tasks-page/filter-recurring @app-state)
     (assoc :recurring-task-id (:id (:tasks-page/filter-recurring @app-state)))
     ;; While viewing a focused issue, keep task re-fetches (e.g. after a done
     ;; toggle) scoped to that issue's tasks so the listing stays consistent.
     (and (= :issues (:active-tab @app-state)) (:issues-page/filter-issue @app-state))
     (assoc :issue-id (:id (:issues-page/filter-issue @app-state))))))

(defn- today-fetch-opts
  ([app-state]
   (today-fetch-opts app-state (:work-private-mode @app-state) (:strict-mode @app-state)))
  ([app-state context strict]
   (merge (category-filters/fetch-opts app-state)
          {:context context
           :strict strict})))

(defn- initialize-tasks-page [app-state fetch-tasks-fn]
  (swap! app-state assoc :tasks-page/collapsed-filters constants/all-category-filters)
  (let [last-sort-mode (:tasks-page/last-sort-mode @app-state)]
    (swap! app-state assoc :sort-mode last-sort-mode))
  (focus-tasks-search)
  (fetch-tasks-fn (tasks-fetch-opts app-state)))

(defn make-tab-initializers
  "What to run when each tab becomes the active one.

  `fetch-category` takes a Group key, and the six Categories tabs are generated
  from `constants/category-groups` rather than listed. They used to be listed,
  and the list named four: the caller already passed a `:fetch-workstreams` and a
  `:fetch-assets`, this destructuring dropped them on the floor, and entering the
  Workstreams or Assets page ran no fetch at all. A Group added to the registry
  now gets its initializer without this map changing."
  [app-state {:keys [fetch-tasks fetch-today-meets fetch-today-journal-entries fetch-messages fetch-resources fetch-issues fetch-today-issues fetch-meets fetch-reports fetch-category fetch-rules-page fetch-mottos fetch-working-on is-admin has-mail]}]
  (into
   (into {} (map (fn [{:keys [tab key]}] [tab (fn [] (fetch-category key))])) constants/category-groups)
   {:tasks (fn []
             (initialize-tasks-page app-state fetch-tasks))
    :today (fn []
             (swap! app-state assoc
                    :today-page/collapsed-filters constants/all-category-filters
                    :sort-mode :today)
             (fetch-tasks (today-fetch-opts app-state))
             (fetch-today-meets (today-fetch-opts app-state))
             (fetch-today-journal-entries (today-fetch-opts app-state))
             (fetch-today-issues)
             (fetch-working-on))
    :mail (fn []
            (when (has-mail)
              (fetch-messages)))
    :resources (fn []
                 (fetch-resources)
                 (focus-input! "resources-filter-search"))
    :issues (fn []
              (fetch-issues)
              (focus-input! "issues-filter-search"))
    :meets (fn []
             (fetch-meets)
             (focus-input! "meets-filter-search"))
    :reports (fn []
               (fetch-reports))
    ;; Rules is in the Categories tab row but is not a Group, so it stays a
    ;; hand-written entry rather than coming out of the registry.
    :cat-rules (fn [] (fetch-rules-page))
    :settings-mottos (fn []
                       (fetch-mottos)
                       (focus-input! "mottos-filter-search"))}))

(def ^:private global-tabs #{:today :tasks :meets :resources :issues :reports :mail})
(def ^:private settings-tabs #{:settings-profile :settings-mottos :settings-shortcuts :settings-history})

(defn- supersection-key [tab]
  (cond
    (global-tabs tab) :last-global-tab
    ;; constants/category-tabs, not a set written out here: this was the hand-written
    ;; one that named four of the six Groups, so leaving Workstreams or Assets
    ;; recorded no last-Categories-tab at all.
    (constants/category-tabs tab) :last-category-tab
    (settings-tabs tab) :last-settings-tab))

(defn set-active-tab [app-state tab-initializers tab]
  (when-let [k (supersection-key (:active-tab @app-state))]
    (swap! app-state assoc k (:active-tab @app-state)))
  (swap! app-state assoc
         :active-tab tab
         :error nil
         :category-selector/open nil
         :category-selector/search ""
         :tasks-page/category-search constants/empty-category-searches
         :today-page/category-search constants/empty-category-searches
         :meets-page/category-search constants/empty-category-searches
         :tasks-page/expanded-task nil
         :today-page/expanded-task nil
         :today-page/expanded-meet nil
         :task-dropdown-open nil)
  (mail-state/reset-mail-page-view-state!)
  (resources-state/reset-resources-page-view-state!)
  (issues-state/reset-issues-page-view-state!)
  (meets-state/reset-meets-page-view-state!)
  (reports-state/reset-reports-page-view-state!)
  (when-not (contains? #{:today :tasks :resources :issues :meets :reports} tab)
    (relations-state/abort-relation-mode))
  (when-let [init-fn (get tab-initializers tab)]
    (init-fn)))

(defn toggle-expanded [app-state page-key task-id]
  (let [collapsing? (= (get @app-state page-key) task-id)]
    (swap! app-state (fn [state]
                       (cond-> (assoc state
                                      page-key (if (= (get state page-key) task-id) nil task-id)
                                      :category-selector/open nil
                                      :category-selector/search ""
                                      :task-dropdown-open nil)
                         (= page-key :today-page/expanded-task) (assoc :today-page/expanded-meet nil)
                         (= page-key :today-page/expanded-meet) (assoc :today-page/expanded-task nil))))
    (when (and collapsing? (= page-key :tasks-page/expanded-task))
      (focus-input! "tasks-filter-search"))))

(defn set-editing [app-state task-id]
  (swap! app-state assoc :editing-task task-id))

(defn clear-editing [app-state]
  (swap! app-state assoc :editing-task nil))

(defn- fetch-opts-for-current-tab [app-state context strict]
  (case (:active-tab @app-state)
    :tasks (tasks-fetch-opts app-state context strict)
    :today (today-fetch-opts app-state context strict)
    {:context context :strict strict}))

(defn set-work-private-mode [app-state fetch-tasks-fn fetch-today-meets-fn fetch-resources-fn fetch-issues-fn fetch-meets-fn fetch-messages-fn fetch-today-journal-entries-fn fetch-reports-fn mode]
  (swap! app-state assoc :work-private-mode mode)
  (case (:active-tab @app-state)
    :resources (fetch-resources-fn)
    :issues (fetch-issues-fn)
    :meets (fetch-meets-fn)
    :mail (fetch-messages-fn)
    :reports (fetch-reports-fn)
    :today (let [opts (fetch-opts-for-current-tab app-state mode (:strict-mode @app-state))]
             (fetch-tasks-fn opts)
             (fetch-today-meets-fn opts)
             (fetch-today-journal-entries-fn opts))
    (fetch-tasks-fn (fetch-opts-for-current-tab app-state mode (:strict-mode @app-state)))))

(defn toggle-strict-mode [app-state fetch-tasks-fn fetch-today-meets-fn fetch-resources-fn fetch-issues-fn fetch-meets-fn fetch-messages-fn fetch-today-journal-entries-fn fetch-reports-fn]
  (let [new-strict (not (:strict-mode @app-state))]
    (swap! app-state assoc :strict-mode new-strict)
    (case (:active-tab @app-state)
      :resources (fetch-resources-fn)
      :issues (fetch-issues-fn)
      :meets (fetch-meets-fn)
      :mail (fetch-messages-fn)
      :reports (fetch-reports-fn)
      :today (let [opts (fetch-opts-for-current-tab app-state (:work-private-mode @app-state) new-strict)]
               (fetch-tasks-fn opts)
               (fetch-today-meets-fn opts)
               (fetch-today-journal-entries-fn opts))
      (fetch-tasks-fn (fetch-opts-for-current-tab app-state (:work-private-mode @app-state) new-strict)))))

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
