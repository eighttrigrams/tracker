(ns et.tr.ui.core
  (:require [reagent.dom.client :as rdomc]
            [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.modals :as modals]
            [et.tr.ui.recording-mode :as recording-mode]
            [et.tr.ui.mail :as mail]
            [et.tr.ui.views.settings :as settings]
            [et.tr.ui.views.mottos :as mottos]
            [et.tr.ui.screensaver :as screensaver]
            [et.tr.ui.views.users :as users]
            [et.tr.ui.views.today :as today]
            [et.tr.ui.views.tasks :as tasks]
            [et.tr.ui.views.categories :as categories]
            [et.tr.ui.views.resources :as resources]
            [et.tr.ui.views.issues :as issues]
            [et.tr.ui.views.meets :as meets]
            [et.tr.ui.views.reports :as reports]
            [et.tr.ui.state.recurring-tasks :as recurring-tasks-state]
            [et.tr.ui.state.issues :as issues-state]
            [et.tr.ui.state.journals :as journals-state]
            [et.tr.ui.state.journal-entries :as journal-entries-state]
            [et.tr.ui.components.controls :as controls]
            [et.tr.ui.constants :as constants]
            [et.tr.i18n :as i18n :refer [t]]))

(defn login-form []
  (let [username (r/atom "")
        password (r/atom "")]
    (fn []
      (let [do-login (fn []
                       (state/login @username @password
                                    (fn []
                                      (reset! username "")
                                      (reset! password ""))))]
        [:div.login-form
         [:h2 (t :auth/login)]
         (when-let [error (:error @state/*app-state)]
           [:div.error error [:button.error-dismiss {:on-click state/clear-error} "×"]])
         [:input {:type "text"
                  :auto-complete "off"
                  :placeholder (t :auth/username)
                  :value @username
                  :on-change #(reset! username (-> % .-target .-value))
                  :on-key-down (fn [e]
                                 (when (= (.-key e) "Enter")
                                   (do-login)))}]
         [:input {:type "password"
                  :placeholder (t :auth/password)
                  :value @password
                  :on-change #(reset! password (-> % .-target .-value))
                  :on-key-down (fn [e]
                                 (when (= (.-key e) "Enter")
                                   (do-login)))}]
         [:button {:on-click (fn [_] (do-login))}
          (t :auth/login)]]))))

(defn- tab-button
  ([active-tab tab-key translation-key]
   (tab-button active-tab tab-key translation-key nil))
  ([active-tab tab-key translation-key active-check]
   [:button.tab
    {:class (when (if active-check
                    (active-check active-tab)
                    (= active-tab tab-key))
              "active")
     :on-click #(state/set-active-tab tab-key)}
    (t translation-key)]))

(def ^:private category-tabs #{:cat-people :cat-places :cat-projects :cat-goals})
(def ^:private settings-tabs #{:settings-profile :settings-mottos :settings-shortcuts :settings-history})

(defn tabs []
  (let [active-tab (:active-tab @state/*app-state)]
    (if (state/is-admin?)
      [:div.tabs
       [tab-button active-tab :users :nav/users]]
      (cond
        (contains? category-tabs active-tab)
        [:div.tabs
         [tab-button active-tab :cat-people :category/people]
         [tab-button active-tab :cat-places :category/places]
         [tab-button active-tab :cat-projects :category/projects]
         [tab-button active-tab :cat-goals :category/goals]]

        (contains? settings-tabs active-tab)
        [:div.tabs
         [tab-button active-tab :settings-profile :settings/profile]
         [tab-button active-tab :settings-mottos :settings/mottos]
         [tab-button active-tab :settings-shortcuts :settings/shortcuts]
         [tab-button active-tab :settings-history :settings/history]]

        :else
        [:div.tabs
         [tab-button active-tab :today :nav/today]
         [tab-button active-tab :meets :nav/meets]
         [tab-button active-tab :issues :nav/issues]
         [tab-button active-tab :tasks :nav/tasks]
         [tab-button active-tab :resources :nav/resources]
         [tab-button active-tab :reports :nav/reports]]))))

(defn- any-modal-open? []
  (or (:editing-modal @state/*app-state)
      (:confirm-undone-task @state/*app-state)
      (:confirm-delete-task @state/*app-state)
      (:confirm-delete-user @state/*app-state)
      (:confirm-delete-category @state/*app-state)
      (:confirm-delete-message @state/*app-state)
      (:confirm-delete-resource @state/*app-state)
      (:confirm-delete-issue @issues-state/*issues-page-state)
      (:confirm-delete-meet @state/*app-state)
      (:confirm-delete-rtask @recurring-tasks-state/*recurring-tasks-page-state)
      (:confirm-delete-journal @journals-state/*journals-page-state)
      (:confirm-delete-entry @journal-entries-state/*journal-entries-page-state)
      (:today-page/confirm-move-to-today @state/*app-state)
      (:create-date-modal @state/*app-state)
      (:create-task-modal @state/*app-state)
      (:reminder-modal @state/*app-state)
      (:done-date-modal @state/*app-state)))

(defn- body-scroll-lock []
  (let [modal-open? (any-modal-open?)]
    (if modal-open?
      (set! (.. js/document -body -style -overflow) "hidden")
      (set! (.. js/document -body -style -overflow) ""))
    nil))

(defn app []
  (let [{:keys [auth-required? logged-in? active-tab]} @state/*app-state]
    [:div
     (body-scroll-lock)
     [recording-mode/indicator]
     [modals/confirm-undone-modal]
     [modals/confirm-delete-modal]
     [modals/confirm-delete-user-modal]
     [modals/confirm-delete-category-modal]
     [modals/confirm-delete-message-modal]
     [modals/confirm-delete-resource-modal]
     [modals/confirm-delete-issue-modal]
     [modals/confirm-delete-meet-modal]
     [modals/confirm-delete-meeting-series-modal]
     [modals/confirm-delete-recurring-task-modal]
     [modals/confirm-delete-journal-modal]
     [modals/confirm-delete-journal-entry-modal]
     [modals/edit-item-modal]
     [modals/create-date-modal]
     [modals/create-task-modal]
     [modals/reminder-date-modal]
     [modals/done-date-modal]
     (cond
       (nil? auth-required?)
       [:div (t :auth/loading)]

       (and auth-required? (not logged-in?))
       [login-form]

       :else
       [:div
        [screensaver/watcher]
        (when-let [error (:error @state/*app-state)]
          [:div.error error [:button.error-dismiss {:on-click state/clear-error} "×"]])
        [:div.top-bar
         [tabs]
         [:div.top-bar-right
          (when (contains? #{:today :tasks :resources :issues :meets :reports} active-tab)
            [controls/relation-mode-toggle])
          (when (contains? #{:today :tasks :resources :issues :meets :mail :reports} active-tab)
            [controls/work-private-toggle])
          [controls/user-info]]]
        (case active-tab
          :today [today/today-tab]
          :resources [resources/resources-tab]
          :issues [issues/issues-tab]
          :meets [meets/meets-tab]
          :cat-people [categories/category-cards-page :people]
          :cat-places [categories/category-cards-page :places]
          :cat-projects [categories/category-cards-page :projects]
          :cat-goals [categories/category-cards-page :goals]
          :reports [reports/reports-tab]
          :mail [mail/mail-page]
          :users [users/users-tab]
          :settings-profile [settings/profile-tab]
          :settings-mottos [mottos/mottos-tab]
          :settings-shortcuts [settings/shortcuts-tab]
          :settings-history [settings/history-tab]
          (let [recurring-mode (state/recurring-mode?)
                recurring-filter (state/recurring-filter)]
            [:div.main-layout
             [tasks/sidebar-filters]
             [:div.main-content
              [:div.tasks-header
               [tasks/recurring-toggle]
               (when-not recurring-mode
                 [tasks/importance-filter-toggle])
               (when-not recurring-mode
                 [tasks/sort-mode-toggle])]
              (cond
                recurring-mode
                [:<>
                 [tasks/recurring-search-add-form]
                 [tasks/recurring-tasks-list]]

                recurring-filter
                [:<>
                 [tasks/recurring-filter-bar]
                 [tasks/tasks-list]]

                :else
                [:<>
                 [tasks/combined-search-add-form]
                 [tasks/tasks-list]])]]))])]))

(def ^:private filter-key->category-type
  {:people constants/CATEGORY-TYPE-PERSON
   :places constants/CATEGORY-TYPE-PLACE
   :projects constants/CATEGORY-TYPE-PROJECT
   :goals constants/CATEGORY-TYPE-GOAL})

(defn- handle-category-shortcut [e filter-key toggle-fn]
  (.preventDefault e)
  (when (.-shiftKey e)
    (state/clear-shared-filter (filter-key->category-type filter-key)))
  (toggle-fn filter-key))

(defn- handle-keyboard-shortcuts [e]
  (when-not (any-modal-open?)
    (let [code (.-code e)
          {:keys [active-tab]} @state/*app-state
          tasks-shortcut-keys (tasks/get-tasks-category-shortcut-keys)
          today-shortcut-keys (today/get-today-category-shortcut-keys)
          resources-shortcut-keys (resources/get-resources-category-shortcut-keys)
          issues-shortcut-keys (issues/get-issues-category-shortcut-keys)
          meets-shortcut-keys (meets/get-meets-category-shortcut-keys)
          reports-shortcut-keys (reports/get-reports-category-shortcut-keys)]
      (when (.-altKey e)
      (cond
        (and (.-shiftKey e) (= "KeyW" code))
        (do
          (.preventDefault e)
          (recording-mode/toggle!))

        (= "KeyT" code)
        (do
          (.preventDefault e)
          (if (= :today active-tab)
            (state/set-active-tab :tasks)
            (state/set-active-tab :today)))

        (= "KeyR" code)
        (do
          (.preventDefault e)
          (state/set-active-tab :resources))

        (= "KeyM" code)
        (do
          (.preventDefault e)
          (state/set-active-tab :meets))

        (= "KeyI" code)
        (do
          (.preventDefault e)
          (state/set-active-tab :issues))

        (= "Escape" code)
        (do
          (.preventDefault e)
          (cond
            (= :tasks active-tab) (state/clear-uncollapsed-task-filters)
            (= :today active-tab) (state/clear-uncollapsed-today-filters)
            (= :mail active-tab) (state/clear-all-mail-filters)
            (= :resources active-tab) (state/clear-uncollapsed-resource-filters)
            (= :issues active-tab) (state/clear-uncollapsed-issue-filters)
            (= :meets active-tab) (state/clear-uncollapsed-meet-filters)
            (= :reports active-tab) (state/clear-uncollapsed-report-filters)))

        (= :tasks active-tab)
        (when-let [filter-key (tasks-shortcut-keys code)]
          (handle-category-shortcut e filter-key state/toggle-filter-collapsed))

        (= :today active-tab)
        (when-let [filter-key (today-shortcut-keys code)]
          (handle-category-shortcut e filter-key state/toggle-today-filter-collapsed))

        (= :resources active-tab)
        (when-let [filter-key (resources-shortcut-keys code)]
          (handle-category-shortcut e filter-key state/toggle-resources-filter-collapsed))

        (= :issues active-tab)
        (when-let [filter-key (issues-shortcut-keys code)]
          (handle-category-shortcut e filter-key state/toggle-issues-filter-collapsed))

        (= :meets active-tab)
        (when-let [filter-key (meets-shortcut-keys code)]
          (handle-category-shortcut e filter-key state/toggle-meets-filter-collapsed))

        (= :reports active-tab)
        (when-let [filter-key (reports-shortcut-keys code)]
          (handle-category-shortcut e filter-key state/toggle-reports-filter-collapsed)))))))

(defonce root (rdomc/create-root (.getElementById js/document "app")))

(defn init []
  (i18n/load-translations!
   (fn []
     (state/fetch-auth-required)
     (.addEventListener js/document "click"
                        (fn [_]
                          (when (:category-selector/open @state/*app-state)
                            (state/close-category-selector))))
     (.removeEventListener js/document "keydown" handle-keyboard-shortcuts)
     (.addEventListener js/document "keydown" handle-keyboard-shortcuts)
     (set! (.-onpopstate js/window)
           (fn [_]
             (when (:editing-modal @state/*app-state)
               (swap! state/*app-state assoc :editing-modal nil))))
     (rdomc/render root [app]))))
