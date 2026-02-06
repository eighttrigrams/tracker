(ns et.tr.ui.views.tasks
  (:require [et.tr.ui.state :as state]
            [et.tr.ui.date :as date]
            [et.tr.ui.components.drag-drop :as drag-drop]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.ui.views.today :as today]
            [et.tr.i18n :refer [t]]))

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
  (let [input-value (:tasks-page/filter-search @state/*app-state)]
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

(defn- sort-mode-button [current-mode mode label-key]
  [:button {:class (when (= current-mode mode) "active")
            :on-click #(when (not= current-mode mode) (state/set-sort-mode mode))}
   (t label-key)])

(defn sort-mode-toggle []
  (let [sort-mode (:sort-mode @state/*app-state)]
    [:div.sort-toggle.toggle-group
     [sort-mode-button sort-mode :manual :tasks/sort-manual]
     [sort-mode-button sort-mode :due-date :tasks/sort-due-date]
     [sort-mode-button sort-mode :recent :tasks/sort-recent]
     [sort-mode-button sort-mode :done :tasks/sort-done]]))

(defn importance-filter-toggle []
  (let [importance-filter (:tasks-page/importance-filter @state/*app-state)]
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

(def ^:private sidebar-filter-configs
  [{:filter-key :people
    :title-key :category/people
    :items-key :people
    :filter-state-key :tasks-page/filter-people
    :category-type state/CATEGORY-TYPE-PERSON}
   {:filter-key :places
    :title-key :category/places
    :items-key :places
    :filter-state-key :tasks-page/filter-places
    :category-type state/CATEGORY-TYPE-PLACE}
   {:filter-key :projects
    :title-key :category/projects
    :items-key :projects
    :filter-state-key :tasks-page/filter-projects
    :category-type state/CATEGORY-TYPE-PROJECT}
   {:filter-key :goals
    :title-key :category/goals
    :items-key :goals
    :filter-state-key :tasks-page/filter-goals
    :category-type state/CATEGORY-TYPE-GOAL}])

(defn sidebar-filters []
  (let [app-state @state/*app-state
        collapsed-filters (:tasks-page/collapsed-filters app-state)]
    (into [:div.sidebar]
          (for [{:keys [filter-key title-key items-key filter-state-key category-type]} sidebar-filter-configs]
            [filter-section {:title (t title-key)
                             :filter-key filter-key
                             :items (get app-state items-key)
                             :selected-ids (get app-state filter-state-key)
                             :toggle-fn #(state/toggle-filter category-type %)
                             :clear-fn #(state/clear-filter category-type)
                             :collapsed? (contains? collapsed-filters filter-key)
                             :number (tasks-category-shortcut-numbers filter-key)}]))))


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
  (let [{:keys [people places projects goals tasks-page/expanded-task editing-task sort-mode drag-task drag-over-task]} @state/*app-state
        tasks (state/filtered-tasks)
        manual-mode? (= sort-mode :manual)
        due-date-mode? (= sort-mode :due-date)
        done-mode? (= sort-mode :done)
        any-task-open? (or expanded-task editing-task)
        drag-enabled? (and manual-mode? (not any-task-open?))]
    (into [:ul.items]
      (for [task tasks]
        (let [is-expanded (= expanded-task (:id task))
              is-editing (= editing-task (:id task))
              is-dragging (= drag-task (:id task))
              is-drag-over (= drag-over-task (:id task))]
          ^{:key (:id task)}
          [:li {:class (str (when is-expanded "expanded")
                            (when is-dragging " dragging")
                            (when is-drag-over " drag-over")
                            (when-not drag-enabled? " drag-disabled"))
                :draggable drag-enabled?
                :on-drag-start (drag-drop/make-drag-start-handler task state/set-drag-task drag-enabled?)
                :on-drag-end (fn [_] (state/clear-drag-state))
                :on-drag-over (drag-drop/make-drag-over-handler task state/set-drag-over-task drag-enabled?)
                :on-drag-leave (drag-drop/make-drag-leave-handler drag-over-task task #(state/set-drag-over-task nil))
                :on-drop (drag-drop/make-drop-handler drag-task task state/reorder-task drag-enabled?)}
           (if is-editing
             [task-item/task-edit-form task]
             [task-item-content task is-expanded people places projects goals done-mode? due-date-mode? manual-mode?])])))))
