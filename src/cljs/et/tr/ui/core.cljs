(ns et.tr.ui.core
  (:require [reagent.dom :as rdom]
            [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.state.tasks-page :as tasks-page]
            [et.tr.ui.modals :as modals]
            [et.tr.ui.mail :as mail]
            [et.tr.ui.views.settings :as settings]
            [et.tr.ui.views.users :as users]
            [et.tr.i18n :as i18n :refer [t tf]]
            [et.tr.filters :as filters]
            ["marked" :refer [marked]]))

(def ^:private tasks-category-shortcut-keys
  {"Digit1" :people
   "Digit2" :places
   "Digit3" :projects
   "Digit4" :goals})

(def ^:private today-category-shortcut-keys
  {"Digit1" :places
   "Digit2" :projects})

(def ^:private tasks-category-shortcut-numbers
  (into {} (map (fn [[k v]] [v (subs k 5)]) tasks-category-shortcut-keys)))

(def ^:private today-category-shortcut-numbers
  (into {} (map (fn [[k v]] [v (subs k 5)]) today-category-shortcut-keys)))

(defn- reset-input-and-filter! [input-atom filter-value]
  (reset! input-atom filter-value)
  (state/set-filter-search filter-value))

(defn- handle-escape-key [input-atom on-clear-fn]
  (fn [e]
    (when (= (.-key e) "Escape")
      (reset! input-atom "")
      (when on-clear-fn (on-clear-fn)))))

(defn- handle-combined-keys [input-value]
  (fn [e]
    (cond
      (and (.-altKey e) (= (.-key e) "Enter"))
      (when (seq input-value)
        (.preventDefault e)
        (state/add-task input-value (fn [] (state/set-filter-search ""))))

      (= (.-key e) "Escape")
      (state/set-filter-search "")

      :else nil)))

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
         (when-let [error (:error @state/app-state)]
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

(defn combined-search-add-form []
  (let [input-value (:tasks-page/filter-search @state/app-state)]
    [:div.combined-search-add-form
     [:input#tasks-filter-search
      {:type "text"
       :placeholder (t :tasks/search-or-add)
       :value input-value
       :on-change #(state/set-filter-search (-> % .-target .-value))
       :on-key-down (handle-combined-keys input-value)}]
     [:button {:on-click #(when (seq input-value)
                            (state/add-task input-value
                                          (fn [] (state/set-filter-search ""))))}
      (t :tasks/add-button)]
     (when (seq input-value)
       [:button.clear-search {:on-click #(state/set-filter-search "")} "x"])]))

(defn add-entity-form [placeholder add-fn name-atom]
  (fn []
    [:div.add-entity-form
     [:input {:type "text"
              :placeholder placeholder
              :value @name-atom
              :on-change #(reset! name-atom (-> % .-target .-value))
              :on-key-down #(when (= (.-key %) "Enter")
                              (add-fn @name-atom (fn [] (reset! name-atom ""))))}]
     [:button {:on-click #(add-fn @name-atom (fn [] (reset! name-atom "")))}
      "+"]]))

(defn- filter-by-name [items filter-text]
  (if (empty? filter-text)
    items
    (filter #(filters/multi-prefix-matches? (:name %) filter-text) items)))

(def ^:private tab-config
  [{:key :today      :translation :nav/today}
   {:key :tasks      :translation :nav/tasks}
   {:key :categories :translation :nav/categories :children [:people-places :projects-goals]}
   {:key :mail       :translation :nav/mail       :admin-only true}
   {:key :users      :translation :nav/users      :admin-only true}
   {:key :settings   :translation :nav/settings}])

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
  (let [active-tab (:active-tab @state/app-state)]
    [:div.tabs
     [tab-button active-tab :today :nav/today]
     [tab-button active-tab :tasks :nav/tasks]
     [tab-button active-tab :categories :nav/categories
      #(contains? #{:categories :people-places :projects-goals} %)]
     (when (state/is-admin?)
       [tab-button active-tab :mail :nav/mail])]))

(defn task-category-badges [task]
  (let [all-categories (concat
                        (map #(assoc % :type state/CATEGORY-TYPE-PERSON) (:people task))
                        (map #(assoc % :type state/CATEGORY-TYPE-PLACE) (:places task))
                        (map #(assoc % :type state/CATEGORY-TYPE-PROJECT) (:projects task))
                        (map #(assoc % :type state/CATEGORY-TYPE-GOAL) (:goals task)))
        importance (:importance task)
        importance-stars (case importance
                           "important" "★"
                           "critical" "★★"
                           nil)
        has-filters? (state/has-active-filters?)]
    (when (or importance-stars (seq all-categories))
      [:div.task-badges
       (when importance-stars
         [:span.importance-badge {:class importance} importance-stars])
       (doall
        (for [category all-categories]
          ^{:key (str (:type category) "-" (:id category))}
          [:span.tag {:class (:type category)
                      :style (when-not has-filters? {:cursor "pointer"})
                      :on-click (when-not has-filters?
                                  (fn [e]
                                    (.stopPropagation e)
                                    (state/toggle-filter (:type category) (:id category))))}
           (:name category)]))])))

(defn- vesicapiscis-icon [selected-scope strict-mode?]
  (let [is-active (= selected-scope "both")
        left-crescent (and (= selected-scope "private") strict-mode?)
        right-crescent (and (= selected-scope "work") strict-mode?)
        left-filled (and (= selected-scope "private") (not strict-mode?))
        right-filled (and (= selected-scope "work") (not strict-mode?))
        both-filled (and (= selected-scope "both") (not strict-mode?))
        intersection-only (and (= selected-scope "both") strict-mode?)
        fill-color (if is-active "white" "var(--accent)")
        stroke-color (if is-active "white" "var(--accent)")
        uid (str (random-uuid))]
    [:svg {:width "32" :height "20" :viewBox "0 0 32 20" :style {:display "inline-block" :vertical-align "middle"}}
     [:defs
      [:mask {:id (str "left-only-" uid)}
       [:rect {:x "0" :y "0" :width "32" :height "20" :fill "white"}]
       [:circle {:cx "21" :cy "10" :r "7" :fill "black"}]]
      [:mask {:id (str "right-only-" uid)}
       [:rect {:x "0" :y "0" :width "32" :height "20" :fill "white"}]
       [:circle {:cx "11" :cy "10" :r "7" :fill "black"}]]]
     (when left-crescent
       [:circle {:cx "11" :cy "10" :r "7" :fill fill-color :stroke "none" :mask (str "url(#left-only-" uid ")")}])
     (when right-crescent
       [:circle {:cx "21" :cy "10" :r "7" :fill fill-color :stroke "none" :mask (str "url(#right-only-" uid ")")}])
     (when left-filled
       [:circle {:cx "11" :cy "10" :r "7" :fill fill-color :stroke "none"}])
     (when right-filled
       [:circle {:cx "21" :cy "10" :r "7" :fill fill-color :stroke "none"}])
     (when both-filled
       [:g
        [:circle {:cx "11" :cy "10" :r "7" :fill fill-color :stroke "none"}]
        [:circle {:cx "21" :cy "10" :r "7" :fill fill-color :stroke "none"}]])
     (when intersection-only
       [:path {:d "M 16 3.5 A 7 7 0 0 1 16 16.5 A 7 7 0 0 1 16 3.5 Z" :fill fill-color :stroke "none"}])
     [:circle {:cx "11" :cy "10" :r "7" :fill "none" :stroke stroke-color :stroke-width "1"}]
     [:circle {:cx "21" :cy "10" :r "7" :fill "none" :stroke stroke-color :stroke-width "1"}]
     (when left-crescent
       [:circle {:cx "21" :cy "10" :r "6.5" :fill "var(--glass-bg)" :stroke "none"}])
     (when right-crescent
       [:circle {:cx "11" :cy "10" :r "6.5" :fill "var(--glass-bg)" :stroke "none"}])]))

(defn- scope-toggle [css-class current-value on-change]
  (into [:div {:class css-class}]
        (for [scope ["private" "both" "work"]]
          (if (= scope "both")
            [:button.toggle-option
             {:key scope
              :class (when (= current-value scope) "active")
              :on-click (fn [e]
                          (.stopPropagation e)
                          (if (= current-value "both")
                            (state/toggle-strict-mode)
                            (on-change scope)))}
             [vesicapiscis-icon current-value (:strict-mode @state/app-state)]]
            [:button.toggle-option
             {:key scope
              :class (when (= current-value scope) "active")
              :on-click (fn [e]
                          (.stopPropagation e)
                          (on-change scope))}
             (t (keyword "toggle" scope))]))))

(defn task-scope-selector [task]
  (let [scope (or (:scope task) "both")]
    [:div.task-scope-selector.toggle-group.compact
     (for [s ["private" "both" "work"]]
       ^{:key s}
       [:button.toggle-option
        {:class (when (= scope s) "active")
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-task-scope (:id task) s))}
        s])]))

(defn task-importance-selector [task]
  (let [importance (or (:importance task) "normal")
        labels {"normal" "○" "important" "★" "critical" "★★"}]
    [:div.task-importance-selector.toggle-group.compact
     (for [level ["normal" "important" "critical"]]
       ^{:key level}
       [:button.toggle-option
        {:class (str level (when (= importance level) " active"))
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-task-importance (:id task) level))}
        (labels level)])]))

(defn task-urgency-selector [task]
  (let [urgency (or (:urgency task) "default")
        labels {"default" "—" "urgent" "!" "superurgent" "!!"}]
    [:div.task-urgency-selector.toggle-group.compact
     (for [level ["default" "urgent" "superurgent"]]
       ^{:key level}
       [:button.toggle-option
        {:class (str level (when (= urgency level) " active"))
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-task-urgency (:id task) level))}
        (labels level)])]))

(defn task-attribute-selectors
  "Renders scope, importance, and urgency selectors for a task.
  Options:
    :show-importance? - Whether to show importance selector (default: true)
    :show-urgency?    - Whether to show urgency selector (default: true)
                        Set to false for views where urgency is implicit (e.g. Today page)"
  [task & {:keys [show-importance? show-urgency?]
           :or {show-importance? true show-urgency? true}}]
  [:<>
   [task-scope-selector task]
   (when show-importance?
     [task-importance-selector task])
   (when show-urgency?
     [task-urgency-selector task])])

(defn task-combined-action-button [task]
  [:div.combined-button-wrapper
   [:button.combined-main-btn
    {:class (if (state/task-done? task) "undone" "done")
     :on-click #(state/set-task-done (:id task) (not (state/task-done? task)))}
    (if (state/task-done? task)
      (t :task/set-undone)
      (t :task/mark-done))]
   [:button.combined-dropdown-btn
    {:class (if (state/task-done? task) "undone" "done")
     :on-click #(state/set-task-dropdown-open (:id task))}
    "▼"]
   (when (= (:id task) (:task-dropdown-open @state/app-state))
     [:div.task-dropdown-menu
      [:button.dropdown-item
       {:on-click #(do
                     (state/set-task-dropdown-open nil)
                     (state/set-confirm-delete-task task))}
       (t :task/delete)]])])

(defn- markdown [text]
  [:div.markdown-content
   {:dangerouslySetInnerHTML {:__html (marked (or text ""))}}])

(defn- time-picker [task & {:keys [show-clear?] :or {show-clear? false}}]
  [:span.time-picker-wrapper
   {:on-click #(.stopPropagation %)}
   [:input.time-picker-input
    {:type "time"
     :value (or (:due_time task) "")
     :on-change (fn [e]
                  (let [v (.. e -target -value)]
                    (state/set-task-due-time (:id task) (when (seq v) v))))}]
   [:button.clock-icon {:on-click (fn [e]
                                    (.stopPropagation e)
                                    (-> e .-currentTarget .-parentElement (.querySelector "input") .showPicker))}
    "🕐"]
   (when (and show-clear? (seq (:due_time task)))
     [:button.clear-time {:on-click (fn [e]
                                      (.stopPropagation e)
                                      (state/set-task-due-time (:id task) nil))}
      "✕"])])

(defn- today-task-edit-form [task]
  (let [title (r/atom (:title task))
        description (r/atom (or (:description task) ""))]
    (fn []
      [item-edit-form
       {:title-atom title
        :description-atom description
        :tags-atom nil
        :title-placeholder (t :task/title-placeholder)
        :description-placeholder (t :task/description-placeholder)
        :on-save (fn []
                   (state/update-task (:id task) @title @description (:tags task)
                                      #(state/clear-editing)))
        :on-cancel #(state/clear-editing)}])))

(defn- today-task-expanded-details [task]
  (let [editing-task (:editing-task @state/app-state)
        is-editing (= editing-task (:id task))]
    (if is-editing
      [today-task-edit-form task]
      [:div.today-task-details
       (when (seq (:description task))
         [:div.item-description [markdown (:description task)]])
       [task-category-badges task]
       [:div.item-actions
        [task-attribute-selectors task]
        [task-combined-action-button task]]])))

(defn today-task-item [task & {:keys [show-day-of-week show-day-prefix overdue?] :or {show-day-of-week false show-day-prefix false overdue? false}}]
  (let [show-prefix? (and show-day-prefix (state/within-days? (:due_date task) 6))
        expanded-task (:today-page/expanded-task @state/app-state)
        editing-task (:editing-task @state/app-state)
        is-expanded (= expanded-task (:id task))
        is-editing (= editing-task (:id task))]
    [:div.today-task-item {:class (when is-expanded "expanded")}
     [:div.today-task-header
      {:on-click #(state/toggle-expanded :today-page/expanded-task (:id task))}
      [:div.today-task-content
       [:span.task-title
        (when show-prefix?
          [:span.task-day-prefix (str (state/get-day-name (:due_date task))
                                      (when (seq (:due_time task)) ","))])
        (when (seq (:due_time task))
          [:span.task-time {:class (when overdue? "overdue-time")} (:due_time task)])
        (:title task)
        (when (and is-expanded (not is-editing))
          [:button.edit-icon {:on-click (fn [e]
                                          (.stopPropagation e)
                                          (state/set-editing (:id task)))}
           "✎"])
        (when (and is-expanded (seq (:due_time task)))
          [time-picker task])]
       (when-not is-expanded
         [task-category-badges task])]
      [:span.task-date
       (cond
         show-prefix? (:due_date task)
         show-day-of-week (state/format-date-with-day (:due_date task))
         :else (:due_date task))]]
     (when is-expanded
       [today-task-expanded-details task])]))

(defn horizon-selector []
  (let [horizon (:upcoming-horizon @state/app-state)]
    [:div.horizon-selector
     [:span (t :today/show)]
     [:button {:class (when (= horizon :three-days) "active")
               :on-click #(state/set-upcoming-horizon :three-days)} (t :today/three-days)]
     [:button {:class (when (= horizon :week) "active")
               :on-click #(state/set-upcoming-horizon :week)} (t :today/week)]
     [:button {:class (when (= horizon :month) "active")
               :on-click #(state/set-upcoming-horizon :month)} (t :today/month)]
     [:button {:class (when (= horizon :three-months) "active")
               :on-click #(state/set-upcoming-horizon :three-months)} (t :today/three-months)]
     [:button {:class (when (= horizon :year) "active")
               :on-click #(state/set-upcoming-horizon :year)} (t :today/year)]
     [:button {:class (when (= horizon :eighteen-months) "active")
               :on-click #(state/set-upcoming-horizon :eighteen-months)} (t :today/eighteen-months)]]))

(defn- handle-filter-badge-click [toggle-fn input-id item-id]
  (toggle-fn item-id)
  (js/setTimeout #(when-let [el (.getElementById js/document input-id)]
                    (.focus el)
                    (let [len (.-length (.-value el))]
                      (.setSelectionRange el len len))) 0))

(defn category-filter-section
  [{:keys [title shortcut-number filter-key items marked-ids toggle-fn clear-fn collapsed?
           toggle-collapsed-fn set-search-fn search-state-path
           section-class item-active-class label-class page-prefix]}]
  (let [marked-items (filter #(contains? marked-ids (:id %)) items)
        search-term (get-in @state/app-state search-state-path "")
        visible-items (if (seq search-term)
                        (filter #(tasks-page/prefix-matches? (:name %) search-term) items)
                        items)
        input-id (str (or page-prefix "tasks") "-filter-" (name filter-key))
        handle-key-down (fn [e]
                          (cond
                            (= (.-key e) "Escape")
                            (do
                              (when (.-altKey e)
                                (.stopPropagation e)
                                (clear-fn)
                                (set-search-fn filter-key ""))
                              (toggle-collapsed-fn filter-key)
                              (state/focus-tasks-search))

                            (= (.-key e) "Enter")
                            (when-let [first-item (first visible-items)]
                              (.preventDefault e)
                              (toggle-fn (:id first-item)))))]
    [:div.filter-section {:class section-class}
     [:div.filter-header
      [:button.collapse-toggle
       {:on-click #(toggle-collapsed-fn filter-key)}
       (if collapsed? ">" "v")]
      [:span.filter-title (when shortcut-number {:title (str "Press Option+" shortcut-number " to toggle")})
       (if shortcut-number (str shortcut-number " " title) title)]
      (when (seq marked-ids)
        [:button.clear-filter {:on-click clear-fn} "x"])]
     (if collapsed?
       (when (seq marked-items)
         [:div.filter-items.collapsed
          (doall
           (for [item marked-items]
             ^{:key (:id item)}
             [:span.filter-item-label {:class label-class}
              (:name item)
              [:button.remove-item {:on-click #(toggle-fn (:id item))} "x"]]))])
       [:div.filter-items
        [:input.category-search
         {:id input-id
          :type "text"
          :placeholder (t :category/search)
          :value search-term
          :on-change #(set-search-fn filter-key (-> % .-target .-value))
          :on-key-down handle-key-down}]
        (doall
         (for [item visible-items]
           ^{:key (:id item)}
           [:button.filter-item
            {:class (when (contains? marked-ids (:id item)) item-active-class)
             :on-click #(handle-filter-badge-click toggle-fn input-id (:id item))}
            (:name item)]))])]))

(defn today-sidebar-filters []
  (let [{:keys [places projects]} @state/app-state
        excluded-places (:today-page/excluded-places @state/app-state)
        excluded-projects (:today-page/excluded-projects @state/app-state)
        collapsed-filters (:today-page/collapsed-filters @state/app-state)]
    [:div.sidebar
     [category-filter-section {:title (t :category/places)
                               :shortcut-number (today-category-shortcut-numbers :places)
                               :filter-key :places
                               :items places
                               :marked-ids excluded-places
                               :toggle-fn state/toggle-today-excluded-place
                               :clear-fn state/clear-today-excluded-places
                               :collapsed? (contains? collapsed-filters :places)
                               :toggle-collapsed-fn state/toggle-today-filter-collapsed
                               :set-search-fn state/set-today-category-search
                               :search-state-path [:today-page/category-search :places]
                               :section-class "exclusion-filter"
                               :item-active-class "excluded"
                               :label-class "excluded"
                               :page-prefix "today"}]
     [category-filter-section {:title (t :category/projects)
                               :shortcut-number (today-category-shortcut-numbers :projects)
                               :filter-key :projects
                               :items projects
                               :marked-ids excluded-projects
                               :toggle-fn state/toggle-today-excluded-project
                               :clear-fn state/clear-today-excluded-projects
                               :collapsed? (contains? collapsed-filters :projects)
                               :toggle-collapsed-fn state/toggle-today-filter-collapsed
                               :set-search-fn state/set-today-category-search
                               :search-state-path [:today-page/category-search :projects]
                               :section-class "exclusion-filter"
                               :item-active-class "excluded"
                               :label-class "excluded"
                               :page-prefix "today"}]]))

(defn- task-list-section [tasks & opts]
  (let [opts-map (apply hash-map opts)]
    [:div.task-list
     (doall
      (for [task tasks]
        ^{:key (:id task)}
        [today-task-item task
         :overdue? (get opts-map :overdue? false)
         :show-day-of-week (get opts-map :show-day-of-week false)
         :show-day-prefix (get opts-map :show-day-prefix false)]))]))

(defn- today-overdue-section [overdue]
  (when (seq overdue)
    [:div.today-section.overdue
     [:h3 (t :today/overdue)]
     [task-list-section overdue :overdue? true]]))

(defn- today-today-section [today]
  [:div.today-section.today
   [:h3 (state/today-formatted)]
   (if (seq today)
     [task-list-section today]
     [:p.empty-message (t :today/no-today)])])

(defn- today-urgent-section [urgent]
  (when (seq urgent)
    [:div.today-section.urgent
     [:h3 (t :today/urgent-matters)]
     [task-list-section urgent :show-day-of-week true]]))

(defn- today-upcoming-section [upcoming]
  [:div.today-section.upcoming
   [:div.section-header
    [:h3 (t :today/upcoming)]
    [horizon-selector]]
   (if (seq upcoming)
     [task-list-section upcoming :show-day-of-week true :show-day-prefix true]
     [:p.empty-message (t :today/no-upcoming)])])

(defn- today-view-switcher []
  (let [selected-view (:today-page/selected-view @state/app-state)]
    [:div.today-view-switcher.toggle-group
     [:button {:class (when (= selected-view :urgent) "active")
               :on-click #(state/set-today-selected-view :urgent)}
      (t :today/urgent-matters)]
     [:button {:class (when (= selected-view :upcoming) "active")
               :on-click #(state/set-today-selected-view :upcoming)}
      (t :today/upcoming)]]))

(defn today-tab []
  (let [overdue (state/overdue-tasks)
        today (state/today-tasks)
        urgent (state/urgent-tasks)
        upcoming (state/upcoming-tasks)
        selected-view (:today-page/selected-view @state/app-state)]
    [:div.main-layout
     [today-sidebar-filters]
     [:div.main-content.today-content
      [today-overdue-section overdue]
      [today-today-section today]
      [today-view-switcher]
      (when (= selected-view :urgent)
        [today-urgent-section urgent])
      (when (= selected-view :upcoming)
        [today-upcoming-section upcoming])]]))

(defn sort-mode-toggle []
  (let [sort-mode (:sort-mode @state/app-state)]
    [:div.sort-toggle.toggle-group
     [:button {:class (when (= sort-mode :manual) "active")
               :on-click #(when (not= sort-mode :manual) (state/set-sort-mode :manual))}
      (t :tasks/sort-manual)]
     [:button {:class (when (= sort-mode :due-date) "active")
               :on-click #(when (not= sort-mode :due-date) (state/set-sort-mode :due-date))}
      (t :tasks/sort-due-date)]
     [:button {:class (when (= sort-mode :recent) "active")
               :on-click #(when (not= sort-mode :recent) (state/set-sort-mode :recent))}
      (t :tasks/sort-recent)]
     [:button {:class (when (= sort-mode :done) "active")
               :on-click #(when (not= sort-mode :done) (state/set-sort-mode :done))}
      (t :tasks/sort-done)]]))

(defn importance-filter-toggle []
  (let [importance-filter (:tasks-page/importance-filter @state/app-state)]
    [:div.importance-filter-toggle.toggle-group
     [:button {:class (when (nil? importance-filter) "active")
               :on-click #(state/set-importance-filter nil)
               :title (t :importance/filter-off)}
      "○"]
     [:button {:class (str "important" (when (= importance-filter :important) " active"))
               :on-click #(state/set-importance-filter :important)
               :title (t :importance/filter-important)}
      "★"]
     [:button {:class (str "critical" (when (= importance-filter :critical) " active"))
               :on-click #(state/set-importance-filter :critical)
               :title (t :importance/filter-critical)}
      "★★"]]))

(defn filter-section [{:keys [title filter-key items selected-ids toggle-fn clear-fn collapsed? number]}]
  [category-filter-section {:title title
                            :shortcut-number number
                            :filter-key filter-key
                            :items items
                            :marked-ids selected-ids
                            :toggle-fn toggle-fn
                            :clear-fn clear-fn
                            :collapsed? collapsed?
                            :toggle-collapsed-fn state/toggle-filter-collapsed
                            :set-search-fn state/set-category-search
                            :search-state-path [:tasks-page/category-search filter-key]
                            :section-class nil
                            :item-active-class "active"
                            :label-class nil}])

(defn sidebar-filters []
  (let [{:keys [people places projects goals]} @state/app-state
        filter-people (:tasks-page/filter-people @state/app-state)
        filter-places (:tasks-page/filter-places @state/app-state)
        filter-projects (:tasks-page/filter-projects @state/app-state)
        filter-goals (:tasks-page/filter-goals @state/app-state)
        collapsed-filters (:tasks-page/collapsed-filters @state/app-state)]
    [:div.sidebar
     [filter-section {:title (t :category/people)
                      :filter-key :people
                      :items people
                      :selected-ids filter-people
                      :toggle-fn #(state/toggle-filter state/CATEGORY-TYPE-PERSON %)
                      :clear-fn state/clear-filter-people
                      :collapsed? (contains? collapsed-filters :people)
                      :number (tasks-category-shortcut-numbers :people)}]
     [filter-section {:title (t :category/places)
                      :filter-key :places
                      :items places
                      :selected-ids filter-places
                      :toggle-fn #(state/toggle-filter state/CATEGORY-TYPE-PLACE %)
                      :clear-fn state/clear-filter-places
                      :collapsed? (contains? collapsed-filters :places)
                      :number (tasks-category-shortcut-numbers :places)}]
     [filter-section {:title (t :category/projects)
                      :filter-key :projects
                      :items projects
                      :selected-ids filter-projects
                      :toggle-fn #(state/toggle-filter state/CATEGORY-TYPE-PROJECT %)
                      :clear-fn state/clear-filter-projects
                      :collapsed? (contains? collapsed-filters :projects)
                      :number (tasks-category-shortcut-numbers :projects)}]
     [filter-section {:title (t :category/goals)
                      :filter-key :goals
                      :items goals
                      :selected-ids filter-goals
                      :toggle-fn #(state/toggle-filter state/CATEGORY-TYPE-GOAL %)
                      :clear-fn state/clear-filter-goals
                      :collapsed? (contains? collapsed-filters :goals)
                      :number (tasks-category-shortcut-numbers :goals)}]]))

(defn- item-edit-form
  [{:keys [title-atom description-atom tags-atom
           title-placeholder description-placeholder tags-placeholder
           on-save on-cancel on-delete]}]
  [:div.item-edit-form
   [:input {:type "text"
            :value @title-atom
            :on-change #(reset! title-atom (-> % .-target .-value))
            :placeholder title-placeholder}]
   [:textarea {:value @description-atom
               :on-change #(reset! description-atom (-> % .-target .-value))
               :placeholder description-placeholder
               :rows 3}]
   (when tags-atom
     [:input {:type "text"
              :value @tags-atom
              :on-change #(reset! tags-atom (-> % .-target .-value))
              :placeholder tags-placeholder}])
   [:div.edit-buttons
    [:button {:on-click on-save} (t :task/save)]
    [:button.cancel {:on-click on-cancel} (t :task/cancel)]
    (when on-delete
      [:button.delete-btn {:on-click on-delete} (t :category/delete)])]])

(defn category-edit-form [item category-type update-fn]
  (let [name-val (r/atom (:name item))
        description-val (r/atom (or (:description item) ""))]
    (fn []
      [item-edit-form
       {:title-atom name-val
        :description-atom description-val
        :title-placeholder (t :category/name-placeholder)
        :description-placeholder (t :category/description-placeholder)
        :on-save (fn []
                   (update-fn (:id item) @name-val @description-val
                              #(state/clear-editing-category)))
        :on-cancel #(state/clear-editing-category)
        :on-delete #(state/set-confirm-delete-category category-type item)}])))

(defn category-item [item category-type update-fn state-key]
  (let [editing (:category-page/editing @state/app-state)
        is-editing (and editing
                        (= (:type editing) category-type)
                        (= (:id editing) (:id item)))
        drag-cat (:drag-category @state/app-state)
        drag-over-cat (:drag-over-category @state/app-state)
        is-dragging (and drag-cat
                         (= (:type drag-cat) state-key)
                         (= (:id drag-cat) (:id item)))
        is-drag-over (and drag-over-cat
                          (= (:type drag-over-cat) state-key)
                          (= (:id drag-over-cat) (:id item)))]
    (if is-editing
      [category-edit-form item category-type update-fn]
      [:li {:class (str (when is-dragging "dragging")
                        (when is-drag-over " drag-over"))
            :draggable (not is-editing)
            :on-click #(state/set-editing-category category-type (:id item))
            :on-drag-start (fn [e]
                             (.setData (.-dataTransfer e) "text/plain" (str (:id item)))
                             (state/set-drag-category state-key (:id item)))
            :on-drag-end (fn [_]
                           (state/clear-category-drag-state))
            :on-drag-over (fn [e]
                            (.preventDefault e)
                            (state/set-drag-over-category state-key (:id item)))
            :on-drag-leave (fn [_]
                             (when (and drag-over-cat
                                        (= (:type drag-over-cat) state-key)
                                        (= (:id drag-over-cat) (:id item)))
                               (state/set-drag-over-category nil nil)))
            :on-drop (fn [e]
                       (.preventDefault e)
                       (when (and drag-cat
                                  (= (:type drag-cat) state-key)
                                  (not= (:id drag-cat) (:id item)))
                         (let [rect (.getBoundingClientRect (.-currentTarget e))
                               y (.-clientY e)
                               mid-y (+ (.-top rect) (/ (.-height rect) 2))
                               position (if (< y mid-y) "before" "after")]
                           (state/reorder-category state-key (:id drag-cat) (:id item) position))))}
       [:span.category-name (:name item)]
       (when (seq (:description item))
         [:span.category-description [markdown (:description item)]])])))

(defn- subtab-button [active-tab tab-key translation-key]
  [:button.subtab
   {:class (when (= active-tab tab-key) "active")
    :on-click #(state/set-active-tab tab-key)}
   (t translation-key)])

(defn- categories-subtabs []
  (let [active-tab (:active-tab @state/app-state)]
    [:div.categories-subtabs
     [subtab-button active-tab :people-places :nav/people-places]
     [subtab-button active-tab :projects-goals :nav/projects-goals]]))

(defn people-places-tab []
  (let [person-name (r/atom "")
        place-name (r/atom "")]
    (fn []
      (let [{:keys [people places]} @state/app-state
            filtered-people (filter-by-name people @person-name)
            filtered-places (filter-by-name places @place-name)]
        [:div.manage-tab
         [:div.manage-section
          [:h3 (t :category/people)]
          [add-entity-form (t :category/add-person) state/add-person person-name]
          [:ul.entity-list
           (doall
            (for [person filtered-people]
              ^{:key (:id person)}
              [category-item person state/CATEGORY-TYPE-PERSON state/update-person :people]))]]
         [:div.manage-section
          [:h3 (t :category/places)]
          [add-entity-form (t :category/add-place) state/add-place place-name]
          [:ul.entity-list
           (doall
            (for [place filtered-places]
              ^{:key (:id place)}
              [category-item place state/CATEGORY-TYPE-PLACE state/update-place :places]))]]]))))

(defn projects-goals-tab []
  (let [project-name (r/atom "")
        goal-name (r/atom "")]
    (fn []
      (let [{:keys [projects goals]} @state/app-state
            filtered-projects (filter-by-name projects @project-name)
            filtered-goals (filter-by-name goals @goal-name)]
        [:div.manage-tab
         [:div.manage-section
          [:h3 (t :category/projects)]
          [add-entity-form (t :category/add-project) state/add-project project-name]
          [:ul.entity-list
           (doall
            (for [project filtered-projects]
              ^{:key (:id project)}
              [category-item project state/CATEGORY-TYPE-PROJECT state/update-project :projects]))]]
         [:div.manage-section
          [:h3 (t :category/goals)]
          [add-entity-form (t :category/add-goal) state/add-goal goal-name]
          [:ul.entity-list
           (doall
            (for [goal filtered-goals]
              ^{:key (:id goal)}
              [category-item goal state/CATEGORY-TYPE-GOAL state/update-goal :goals]))]]]))))

(defn categories-tab []
  (let [active-tab (:active-tab @state/app-state)]
    [:div.categories-page
     [categories-subtabs]
     (case active-tab
       :projects-goals [projects-goals-tab]
       [people-places-tab])]))

(defn category-selector [task category-type entities label]
  (let [selector-id (str (:id task) "-" category-type)
        input-id (str "category-selector-input-" selector-id)]
    (fn [task category-type entities label]
      (let [task-categories (case category-type
                              state/CATEGORY-TYPE-PERSON (:people task)
                              state/CATEGORY-TYPE-PLACE (:places task)
                              state/CATEGORY-TYPE-PROJECT (:projects task)
                              state/CATEGORY-TYPE-GOAL (:goals task))
            task-category-ids (set (map :id task-categories))
            open-selector (:category-selector/open @state/app-state)
            is-open (= open-selector selector-id)
            search-term (:category-selector/search @state/app-state)
            available-entities (remove #(contains? task-category-ids (:id %)) entities)
            filtered-entities (if (and is-open (seq search-term))
                                (filter #(tasks-page/prefix-matches? (:name %) search-term) available-entities)
                                available-entities)]
        [:div.tag-selector
         [:div.category-selector-dropdown
          [:button.category-selector-trigger
           {:class (str category-type (when is-open " open"))
            :on-click (fn [e]
                        (.stopPropagation e)
                        (if is-open
                          (do
                            (state/close-category-selector)
                            (state/focus-tasks-search))
                          (do
                            (state/open-category-selector selector-id)
                            (js/setTimeout #(when-let [el (.getElementById js/document input-id)]
                                              (.focus el)) 0))))}
           (str "+ " label)]
          (when is-open
            [:div.category-selector-panel
             {:on-click #(.stopPropagation %)}
             [:input.category-selector-search
              {:id input-id
               :type "text"
               :placeholder (t :category/search)
               :value search-term
               :auto-focus true
               :on-change #(state/set-category-selector-search (-> % .-target .-value))
               :on-key-down (fn [e]
                              (case (.-key e)
                                "Escape" (do
                                           (state/close-category-selector)
                                           (state/focus-tasks-search))
                                "Enter" (when (= 1 (count filtered-entities))
                                          (state/categorize-task (:id task) category-type (:id (first filtered-entities)))
                                          (state/close-category-selector)
                                          (state/focus-tasks-search))
                                nil))}]
             [:div.category-selector-items
              (if (seq filtered-entities)
                (doall
                 (for [entity filtered-entities]
                   ^{:key (:id entity)}
                   [:button.category-selector-item
                    {:class category-type
                     :on-click (fn [e]
                                 (.stopPropagation e)
                                 (state/categorize-task (:id task) category-type (:id entity))
                                 (state/close-category-selector)
                                 (state/focus-tasks-search))}
                    (:name entity)]))
                [:div.category-selector-empty (t :category/no-results)])]])]
         (doall
          (for [category task-categories]
            ^{:key (str category-type "-" (:id category))}
            [:span.tag
             {:class category-type}
             (:name category)
             [:button.remove-tag
              {:on-click #(state/uncategorize-task (:id task) category-type (:id category))}
              "x"]]))]))))

(defn task-edit-form [task]
  (let [title (r/atom (:title task))
        description (r/atom (or (:description task) ""))
        tags (r/atom (or (:tags task) ""))]
    (fn []
      [item-edit-form
       {:title-atom title
        :description-atom description
        :tags-atom tags
        :title-placeholder (t :task/title-placeholder)
        :description-placeholder (t :task/description-placeholder)
        :tags-placeholder (t :task/tags-placeholder)
        :on-save (fn []
                   (state/update-task (:id task) @title @description @tags
                                      #(state/clear-editing)))
        :on-cancel #(state/clear-editing)}])))

(defn task-categories-readonly [task]
  [:div.item-tags-readonly
   [task-category-badges task]])

(defn- task-expanded-details [task people places projects goals]
  [:div.item-details
   (when (seq (:description task))
     [:div.item-description [markdown (:description task)]])
   [:div.item-tags
    [category-selector task state/CATEGORY-TYPE-PERSON people (t :category/person)]
    [category-selector task state/CATEGORY-TYPE-PLACE places (t :category/place)]
    [category-selector task state/CATEGORY-TYPE-PROJECT projects (t :category/project)]
    [category-selector task state/CATEGORY-TYPE-GOAL goals (t :category/goal)]]
   [:div.item-actions
    [task-attribute-selectors task]
    [task-combined-action-button task]]])

(defn- task-header [task is-expanded done-mode? due-date-mode?]
  [:div.item-header
   {:on-click #(state/toggle-expanded :tasks-page/expanded-task (:id task))}
   [:div.item-title
    (when (seq (:due_time task))
      [:span.task-time (:due_time task)])
    (:title task)
    (when is-expanded
      [:<>
       [:button.edit-icon {:on-click (fn [e]
                                       (.stopPropagation e)
                                       (state/set-editing (:id task)))}
        "✎"]
       [:span.date-picker-wrapper
        {:on-click #(.stopPropagation %)}
        [:input.date-picker-input
         {:type "date"
          :value (or (:due_date task) "")
          :on-change (fn [e]
                       (let [v (.. e -target -value)]
                         (state/set-task-due-date (:id task) (when (seq v) v))))}]
        [:button.calendar-icon {:on-click (fn [e]
                                            (.stopPropagation e)
                                            (-> e .-currentTarget .-parentElement (.querySelector "input") .showPicker))}
         "📅"]]
       (when (:due_date task)
         [time-picker task :show-clear? true])])]
   [:div.item-date
    (when (and (:due_date task) (not done-mode?))
      (let [today (state/today-str)
            overdue? (< (:due_date task) today)]
        [:span.due-date {:class (when overdue? "overdue")} (state/format-date-with-day (:due_date task))]))
    (when-not due-date-mode?
      [:span (:modified_at task)])]])

(defn- task-item-content [task is-expanded people places projects goals done-mode? due-date-mode?]
  [:div
   [task-header task is-expanded done-mode? due-date-mode?]
   (if is-expanded
     [task-expanded-details task people places projects goals]
     [task-categories-readonly task])])

(defn- handle-task-drag-start [task manual-mode?]
  (fn [e]
    (when manual-mode?
      (.setData (.-dataTransfer e) "text/plain" (str (:id task)))
      (state/set-drag-task (:id task)))))

(defn- handle-task-drag-over [task manual-mode?]
  (fn [e]
    (when manual-mode?
      (.preventDefault e)
      (state/set-drag-over-task (:id task)))))

(defn- handle-task-drop [drag-task task manual-mode?]
  (fn [e]
    (.preventDefault e)
    (when (and manual-mode? drag-task (not= drag-task (:id task)))
      (let [rect (.getBoundingClientRect (.-currentTarget e))
            y (.-clientY e)
            mid-y (+ (.-top rect) (/ (.-height rect) 2))
            position (if (< y mid-y) "before" "after")]
        (state/reorder-task drag-task (:id task) position)))))

(defn tasks-list []
  (let [{:keys [people places projects goals tasks-page/expanded-task editing-task sort-mode drag-task drag-over-task]} @state/app-state
        tasks (state/filtered-tasks)
        manual-mode? (= sort-mode :manual)
        due-date-mode? (= sort-mode :due-date)
        done-mode? (= sort-mode :done)]
    (into [:ul.items]
      (for [task tasks]
        (let [is-expanded (= expanded-task (:id task))
              is-editing (= editing-task (:id task))
              is-dragging (= drag-task (:id task))
              is-drag-over (= drag-over-task (:id task))]
          ^{:key (:id task)}
          [:li {:class (str (when is-expanded "expanded")
                            (when is-dragging " dragging")
                            (when is-drag-over " drag-over"))
                :draggable (and manual-mode? (not is-editing))
                :on-drag-start (handle-task-drag-start task manual-mode?)
                :on-drag-end (fn [_] (state/clear-drag-state))
                :on-drag-over (handle-task-drag-over task manual-mode?)
                :on-drag-leave (fn [_]
                                 (when (= drag-over-task (:id task))
                                   (state/set-drag-over-task nil)))
                :on-drop (handle-task-drop drag-task task manual-mode?)}
           (if is-editing
             [task-edit-form task]
             [task-item-content task is-expanded people places projects goals done-mode? due-date-mode?])])))))


(defn user-switcher-dropdown []
  (let [available-users (:available-users @state/app-state)
        current-user (:current-user @state/app-state)]
    [:div.user-switcher-dropdown
     (doall
      (for [user available-users]
        ^{:key (or (:id user) "admin")}
        [:div.user-switcher-item
         {:class (when (= (:id user) (:id current-user)) "active")
          :on-click #(state/switch-user user)}
         (:username user)]))]))

(defn work-private-toggle []
  (let [mode (name (:work-private-mode @state/app-state))]
    [scope-toggle "work-private-toggle toggle-group" mode #(state/set-work-private-mode (keyword %))]))

(defn dark-mode-toggle []
  (let [dark-mode (:dark-mode @state/app-state)]
    [:button.dark-mode-toggle
     {:on-click state/toggle-dark-mode}
     (if dark-mode "\u2600" "\u263E")]))

(defn user-info []
  (let [current-user (:current-user @state/app-state)
        active-tab (:active-tab @state/app-state)
        is-admin (state/is-admin?)
        auth-required? (:auth-required? @state/app-state)
        show-switcher (:show-user-switcher @state/app-state)]
    (when current-user
      [:div.user-info
       (when is-admin
         [:button.users-btn
          {:class (when (= active-tab :users) "active")
           :on-click #(state/set-active-tab :users)}
          (t :nav/users)])
       (if auth-required?
         [:<>
          [:button.username-btn
           {:class (when (= active-tab :settings) "active")
            :on-click #(state/set-active-tab :settings)}
           (:username current-user)]
          [:button.logout-btn {:on-click state/logout} (t :auth/logout)]]
         [:<>
          [:button.settings-btn
           {:class (when (= active-tab :settings) "active")
            :on-click #(state/set-active-tab :settings)}
           (t :nav/settings)]
          [:div.user-switcher-wrapper
           [:button.switch-user-btn
            {:on-click state/toggle-user-switcher}
            [:span.current-user (:username current-user)]
            [:span.dropdown-arrow (if show-switcher "▲" "▼")]]
           (when show-switcher
             [user-switcher-dropdown])]])])))

(defn app []
  (let [{:keys [auth-required? logged-in? active-tab sort-mode]} @state/app-state]
    [:div
     [modals/confirm-delete-modal]
     [modals/confirm-delete-user-modal]
     [modals/confirm-delete-category-modal]
     [modals/confirm-delete-message-modal]
     [modals/pending-task-modal]
     (cond
       (nil? auth-required?)
       [:div (t :auth/loading)]

       (and auth-required? (not logged-in?))
       [login-form]

       :else
       [:div
        (when-let [error (:error @state/app-state)]
          [:div.error error [:button.error-dismiss {:on-click state/clear-error} "×"]])
        [:div.top-bar
         [tabs]
         [:div.top-bar-right
          (when (contains? #{:today :tasks} active-tab)
            [work-private-toggle])
          [dark-mode-toggle]
          [user-info]]]
        (case active-tab
          :today [today-tab]
          :categories [categories-tab]
          :people-places [categories-tab]
          :projects-goals [categories-tab]
          :mail [mail/mail-page]
          :users [users/users-tab]
          :settings [settings/settings-tab]
          [:div.main-layout
           [sidebar-filters]
           [:div.main-content
            [:div.tasks-header
             [:h2 {:title (t :tasks/title-tooltip)} (t :tasks/title)]
             [importance-filter-toggle]
             [sort-mode-toggle]]
            (when (not= sort-mode :done)
              [combined-search-add-form])
            [tasks-list]]])])]))

(defn- get-available-tabs []
  (let [is-admin (state/is-admin?)]
    (->> tab-config
         (filter #(or (not (:admin-only %)) is-admin))
         (mapv :key))))

(defn- navigate-tab [direction]
  (let [tabs (get-available-tabs)
        current (:active-tab @state/app-state)
        effective-current (if (contains? #{:people-places :projects-goals} current)
                            :categories
                            current)
        current-idx (.indexOf tabs effective-current)
        next-idx (mod (+ current-idx direction) (count tabs))]
    (state/set-active-tab (nth tabs next-idx))))

(defn- handle-keyboard-shortcuts [e]
  (let [code (.-code e)
        {:keys [active-tab]} @state/app-state]
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

        (= "Escape" code)
        (do
          (.preventDefault e)
          (cond
            (= :tasks active-tab) (state/clear-uncollapsed-task-filters)
            (= :today active-tab) (state/clear-uncollapsed-today-filters)))

        (= :tasks active-tab)
        (when-let [filter-key (tasks-category-shortcut-keys code)]
          (.preventDefault e)
          (state/toggle-filter-collapsed filter-key))

        (= :today active-tab)
        (when-let [filter-key (today-category-shortcut-keys code)]
          (.preventDefault e)
          (state/toggle-today-filter-collapsed filter-key))))))

(defn init []
  (i18n/load-translations!
   (fn []
     (state/fetch-auth-required)
     (.addEventListener js/document "click"
                        (fn [_]
                          (when (:category-selector/open @state/app-state)
                            (state/close-category-selector))))
     (.removeEventListener js/document "keydown" handle-keyboard-shortcuts)
     (.addEventListener js/document "keydown" handle-keyboard-shortcuts)
     (rdom/render [app] (.getElementById js/document "app")))))
