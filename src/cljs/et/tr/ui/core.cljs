(ns et.tr.ui.core
  (:require [reagent.dom :as rdom]
            [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.modals :as modals]
            [et.tr.ui.mail :as mail]
            [et.tr.ui.views.settings :as settings]
            [et.tr.ui.views.users :as users]
            [et.tr.ui.views.today :as today]
            [et.tr.ui.views.tasks :as tasks]
            [et.tr.ui.views.categories :as categories]
            [et.tr.ui.views.resources :as resources]
            [et.tr.ui.components.controls :as controls]
            [et.tr.i18n :as i18n :refer [t]]))

(def ^:private tab-config
  [{:key :today      :translation :nav/today}
   {:key :tasks      :translation :nav/tasks}
   {:key :resources  :translation :nav/resources}
   {:key :categories :translation :nav/categories :children [:people-places :projects-goals]}
   {:key :mail       :translation :nav/mail       :admin-only true}
   {:key :users      :translation :nav/users      :admin-only true}
   {:key :settings   :translation :nav/settings}])

(defn login-form []
  (let [username (r/atom "")
        password (r/atom "")]
    (fn []
      (let [do-login (fn []
                       (state/login @username @password
                                    (fn []
                                      (reset! username "")
                                      (reset! password "")
                                      (state/fetch-tasks)
                                      (state/fetch-people)
                                      (state/fetch-places)
                                      (state/fetch-projects)
                                      (state/fetch-goals))))]
        [:div.login-form
         [:h2 (t :auth/login)]
         (when-let [error (:error @state/*app-state)]
           [:div.error error [:button.error-dismiss {:on-click state/clear-error} "×"]])
         [:input {:type "text"
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

(defn tabs []
  (let [active-tab (:active-tab @state/*app-state)]
    [:div.tabs
     [tab-button active-tab :today :nav/today]
     [tab-button active-tab :tasks :nav/tasks]
     [tab-button active-tab :resources :nav/resources]
     [tab-button active-tab :categories :nav/categories
      #(contains? #{:categories :people-places :projects-goals} %)]
     (when (state/is-admin?)
       [tab-button active-tab :mail :nav/mail])]))

(defn app []
  (let [{:keys [auth-required? logged-in? active-tab]} @state/*app-state]
    [:div
     [modals/confirm-delete-modal]
     [modals/confirm-delete-user-modal]
     [modals/confirm-delete-category-modal]
     [modals/confirm-delete-message-modal]
     [modals/confirm-delete-resource-modal]
     [modals/pending-task-modal]
     (cond
       (nil? auth-required?)
       [:div (t :auth/loading)]

       (and auth-required? (not logged-in?))
       [login-form]

       :else
       [:div
        (when-let [error (:error @state/*app-state)]
          [:div.error error [:button.error-dismiss {:on-click state/clear-error} "×"]])
        [:div.top-bar
         [tabs]
         [:div.top-bar-right
          (when (contains? #{:today :tasks :resources} active-tab)
            [controls/work-private-toggle])
          [controls/dark-mode-toggle]
          [controls/user-info]]]
        (case active-tab
          :today [today/today-tab]
          :resources [resources/resources-tab]
          :categories [categories/categories-tab]
          :people-places [categories/categories-tab]
          :projects-goals [categories/categories-tab]
          :mail [mail/mail-page]
          :users [users/users-tab]
          :settings [settings/settings-tab]
          ;; Tasks tab layout: main-layout > [sidebar + main-content > [header + search + list]]
          [:div.main-layout
           [tasks/sidebar-filters]
           [:div.main-content
            [:div.tasks-header
             [:h2 {:title (t :tasks/title-tooltip)} (t :tasks/title)]
             [tasks/importance-filter-toggle]
             [tasks/sort-mode-toggle]]
            [tasks/combined-search-add-form]
            [tasks/tasks-list]]])])]))

(defn- get-available-tabs []
  (let [is-admin (state/is-admin?)]
    (->> tab-config
         (filter #(or (not (:admin-only %)) is-admin))
         (mapv :key))))

(defn- navigate-tab [direction]
  (let [tabs (get-available-tabs)
        current (:active-tab @state/*app-state)
        effective-current (if (contains? #{:people-places :projects-goals} current)
                            :categories
                            current)
        current-idx (.indexOf tabs effective-current)
        next-idx (mod (+ current-idx direction) (count tabs))]
    (state/set-active-tab (nth tabs next-idx))))

(defn- handle-keyboard-shortcuts [e]
  (let [code (.-code e)
        {:keys [active-tab]} @state/*app-state
        tasks-shortcut-keys (tasks/get-tasks-category-shortcut-keys)
        today-shortcut-keys (today/get-today-category-shortcut-keys)]
    (when (.-shiftKey e)
      (cond
        (= "ArrowLeft" code)
        (do
          (.preventDefault e)
          (navigate-tab -1))

        (= "ArrowRight" code)
        (do
          (.preventDefault e)
          (navigate-tab 1))))
    (when (.-altKey e)
      (cond
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

        (= "Escape" code)
        (do
          (.preventDefault e)
          (cond
            (= :tasks active-tab) (state/clear-uncollapsed-task-filters)
            (= :today active-tab) (state/clear-uncollapsed-today-filters)
            (= :mail active-tab) (state/clear-all-mail-filters)
            (= :resources active-tab) (state/clear-all-resource-filters)))

        (= :tasks active-tab)
        (when-let [filter-key (tasks-shortcut-keys code)]
          (.preventDefault e)
          (state/toggle-filter-collapsed filter-key))

        (= :today active-tab)
        (when-let [filter-key (today-shortcut-keys code)]
          (.preventDefault e)
          (state/toggle-today-filter-collapsed filter-key))))))

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
     (rdom/render [app] (.getElementById js/document "app")))))
