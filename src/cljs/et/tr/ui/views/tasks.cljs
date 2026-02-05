(ns et.tr.ui.views.tasks
  (:require [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.state.tasks-page :as tasks-page]
            [et.tr.ui.date :as date]
            [et.tr.ui.components.drag-drop :as drag-drop]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.ui.views.today :as today]
            [et.tr.i18n :refer [t]]
            [et.tr.filters :as filters]))

(def ^:private tasks-category-shortcut-keys
  {"Digit1" :people
   "Digit2" :places
   "Digit3" :projects
   "Digit4" :goals})

(def tasks-category-shortcut-numbers
  (into {} (map (fn [[k v]] [v (subs k 5)]) tasks-category-shortcut-keys)))

(defn get-tasks-category-shortcut-keys []
  tasks-category-shortcut-keys)

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

(defn- filter-by-name [items filter-text]
  (if (empty? filter-text)
    items
    (filter #(filters/multi-prefix-matches? (:name %) filter-text) items)))

(defn- sort-mode-button [current-mode mode label-key]
  [:button {:class (when (= current-mode mode) "active")
            :on-click #(when (not= current-mode mode) (state/set-sort-mode mode))}
   (t label-key)])

(defn sort-mode-toggle []
  (let [sort-mode (:sort-mode @state/app-state)]
    [:div.sort-toggle.toggle-group
     [sort-mode-button sort-mode :manual :tasks/sort-manual]
     [sort-mode-button sort-mode :due-date :tasks/sort-due-date]
     [sort-mode-button sort-mode :recent :tasks/sort-recent]
     [sort-mode-button sort-mode :done :tasks/sort-done]]))

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
  [today/category-filter-section {:title title
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

(defn- handle-task-drag-start [task manual-mode?]
  (fn [e]
    (when manual-mode?
      ((drag-drop/make-drag-start-handler task state/set-drag-task) e))))

(defn- handle-task-drag-over [task manual-mode?]
  (fn [e]
    (when manual-mode?
      ((drag-drop/make-drag-over-handler task state/set-drag-over-task) e))))

(defn- handle-task-drop [drag-task task manual-mode?]
  (fn [e]
    (when manual-mode?
      ((drag-drop/make-drop-handler drag-task task state/reorder-task) e))))

(defn- task-expanded-details [task people places projects goals]
  [:div.item-details
   (when (seq (:description task))
     [:div.item-description [task-item/markdown (:description task)]])
   [:div.item-tags
    [task-item/category-selector task state/CATEGORY-TYPE-PERSON people (t :category/person)]
    [task-item/category-selector task state/CATEGORY-TYPE-PLACE places (t :category/place)]
    [task-item/category-selector task state/CATEGORY-TYPE-PROJECT projects (t :category/project)]
    [task-item/category-selector task state/CATEGORY-TYPE-GOAL goals (t :category/goal)]]
   [:div.item-actions
    [task-item/task-attribute-selectors task]
    [task-item/task-combined-action-button task]]])

(defn- task-header [task is-expanded done-mode? due-date-mode? manual-mode?]
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
         [task-item/time-picker task :show-clear? true])])]
   [:div.item-date
    (when (and (:due_date task) (not done-mode?))
      (let [today (date/today-str)
            overdue? (< (:due_date task) today)]
        [:span.due-date {:class (when overdue? "overdue")} (date/format-date-with-day (:due_date task))]))
    (when (and (not due-date-mode?) (not manual-mode?))
      [:span (date/format-datetime-localized (:modified_at task))])]])

(defn- task-item-content [task is-expanded people places projects goals done-mode? due-date-mode? manual-mode?]
  [:div
   [task-header task is-expanded done-mode? due-date-mode? manual-mode?]
   (if is-expanded
     [task-expanded-details task people places projects goals]
     [task-item/task-categories-readonly task])])

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
                :on-drag-leave (drag-drop/make-drag-leave-handler drag-over-task task #(state/set-drag-over-task nil))
                :on-drop (handle-task-drop drag-task task manual-mode?)}
           (if is-editing
             [task-item/task-edit-form task]
             [task-item-content task is-expanded people places projects goals done-mode? due-date-mode? manual-mode?])])))))
