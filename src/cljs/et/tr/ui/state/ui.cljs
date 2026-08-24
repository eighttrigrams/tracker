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

(defn- tasks-fetch-opts [app-state]
  (cond-> (merge (category-filters/fetch-opts app-state)
                 {:search-term (:tasks-page/filter-search @app-state)
                  :importance (:tasks-page/importance-filter @app-state)
                  :context (:work-private-mode @app-state)
                  :strict (:strict-mode @app-state)})
    (:tasks-page/filter-recurring @app-state)
    (assoc :recurring-task-id (:id (:tasks-page/filter-recurring @app-state)))))

(defn- today-fetch-opts [app-state]
  (merge (category-filters/fetch-opts app-state)
         {:context (:work-private-mode @app-state)
          :strict (:strict-mode @app-state)}))

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

;; Both of these took eight fetch functions and dispatched on `:active-tab`
;; themselves. That dispatch was a second, worse copy of state.cljs's
;; `refetch-current-tab`: it knew nothing of the sub-modes, so on the Tasks tab
;; in recurring mode it refetched *plain tasks* and left the recurring list
;; holding whatever it loaded when the view opened — the reported "all items are
;; shown" — and on Today it left the urgent-issues list on the old scope. They
;; take the one refetch function instead, so there is a single dispatch to keep
;; correct. It arrives as a parameter rather than through a require because
;; state.cljs requires this namespace, and that is also why the eight were
;; passed in the first place.
;;
;; The refetch reads the mode off the atom while these used to pass it down
;; explicitly. Equivalent, because the `swap!` storing it runs first — and the
;; Today fan-out, which spreads one opts map over four lists, is covered by
;; scope-switcher-submodes.feature for exactly that reason.

;; The switcher's two keys are the one piece of view state expected to outlive a
;; reload. They decide what every list on every page is allowed to show, so a
;; refresh that quietly reset them to :both/false widens every list at once —
;; which reads as private items appearing on a work screen, not as a forgotten
;; toggle. They go to localStorage rather than onto the user row because the
;; scope is a property of the window being worked in (the work laptop stays on
;; :work) and not of the account, the same reason :dark-mode is not a user
;; setting either. Both keys together: the middle button and a click on the
;; already-active end button toggle :strict-mode, so restoring the mode without
;; the flag would come back as a different selection than the one left behind.
(def ^:private scope-storage-key "scope-switcher")

(def ^:private scope-modes
  "The three the switcher can produce. Anything else in storage is a hand-edit or
  a leftover from a rename, and is dropped here rather than allowed through to
  the fetch layer, which would pass an unknown context to the server as-is."
  #{:both :work :private})

(defn load-scope-from-storage
  "The stored switcher state, shaped to merge over the app-state defaults.

  Returns nil — not the defaults — when nothing usable is stored, so the
  defaults keep being stated in exactly one place (state.cljs's initial map)
  instead of being duplicated here, where the two copies could drift. Every read
  is validated: `JSON.parse` on a hand-edited value throws, a renamed mode
  deserializes to a keyword no longer in `scope-modes`, and a non-object parses
  to something `get` returns nil for.

  An unusable mode discards the flag with it rather than restoring it alone. The
  two are only ever written together, so a blob with a bad mode is a hand-edit or
  a leftover from a rename, and `:strict-mode true` salvaged out of one lands on
  the default `:both` — which is the intersection, the narrowest view the
  switcher has, and nothing the user chose.

  Wrapped in a try because this runs while the namespace loads, before anything
  is rendered, and localStorage access itself throws in some privacy modes.
  Uncaught, that is a blank page rather than a forgotten scope."
  []
  (try
    (let [{:keys [mode strict]} (some-> (.getItem js/localStorage scope-storage-key)
                                       js/JSON.parse
                                       (js->clj :keywordize-keys true))
          mode (keyword mode)]
      (when (contains? scope-modes mode)
        (cond-> {:work-private-mode mode}
          (boolean? strict) (assoc :strict-mode strict))))
    (catch :default _ nil)))

(defn- save-scope-to-storage! [state]
  (try
    (.setItem js/localStorage scope-storage-key
              (js/JSON.stringify #js {"mode" (name (:work-private-mode state))
                                      "strict" (boolean (:strict-mode state))}))
    (catch :default _ nil)))

(defn setup-scope-persistence-watcher
  "Mirror the switcher's two keys into storage whenever either changes.

  A watch, in the shape of the dark-mode one below, rather than a write inside
  `set-work-private-mode` and `toggle-strict-mode` — the two functions that own
  those keys today. A third writer added later cannot then persist nothing,
  which is the failure that reads as \"it forgets the scope, but only when I set
  it from over here\". The guard keeps this off the write path of every other
  `swap!` in the app, of which there is one per keystroke in a search box."
  [app-state]
  (add-watch app-state :scope-persistence
    (fn [_ _ old-state new-state]
      (when (or (not= (:work-private-mode old-state) (:work-private-mode new-state))
                (not= (:strict-mode old-state) (:strict-mode new-state)))
        (save-scope-to-storage! new-state)))))

(defn set-work-private-mode [app-state refetch-fn mode]
  (swap! app-state assoc :work-private-mode mode)
  (refetch-fn))

(defn toggle-strict-mode [app-state refetch-fn]
  (swap! app-state update :strict-mode not)
  (refetch-fn))

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
