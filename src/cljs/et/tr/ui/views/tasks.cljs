(ns et.tr.ui.views.tasks
  (:require [et.tr.ui.state :as state]
            [et.tr.ui.state.recurring-tasks :as recurring-tasks-state]
            [et.tr.ui.date :as date]
            [et.tr.ui.components.drag-drop :as drag-drop]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.ui.components.filter-section :as filter-section]
            [et.tr.ui.components.category-selector :as category-selector]
            [et.tr.ui.components.relation-link :as relation-link]
            [et.tr.ui.components.relation-badges :as relation-badges]
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

(def ^:private clear-search-callback
  (fn [] (state/set-filter-search "")))

(defn- handle-add-task-shortcut [e input-value]
  (when (seq input-value)
    (.preventDefault e)
    (state/add-task input-value clear-search-callback)))

(defn- handle-combined-keys [input-value done-mode?]
  (fn [e]
    (cond
      (and (.-altKey e) (= (.-key e) "Enter") (not done-mode?))
      (handle-add-task-shortcut e input-value)

      (= (.-key e) "Escape")
      (state/set-filter-search "")

      :else nil)))

(defn combined-search-add-form []
  (let [app-state @state/*app-state
        input-value (:tasks-page/filter-search app-state)
        done-mode? (= (:sort-mode app-state) :done)]
    [:div.combined-search-add-form
     [:input#tasks-filter-search
      {:type "text"
       :auto-complete "off"
       :placeholder (if done-mode? (t :tasks/search) (t :tasks/search-or-add))
       :value input-value
       :on-change #(state/set-filter-search (-> % .-target .-value))
       :on-key-down (handle-combined-keys input-value done-mode?)}]
     (when-not done-mode?
       [:button {:on-click #(when (seq input-value)
                              (state/add-task input-value clear-search-callback))}
        (t :tasks/add-button)])
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
     [sort-mode-button sort-mode :recent :tasks/sort-recent]
     [sort-mode-button sort-mode :due-date :tasks/sort-due-date]
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
  [filter-section/category-filter-section {:title title
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
                                  :section-class (name filter-key)
                                  :item-active-class "active"
                                  :label-class nil}])

(def ^:private sidebar-filter-configs
  [{:filter-key :people
    :title-key :category/people
    :items-key :people
    :filter-state-key :shared/filter-people
    :category-type state/CATEGORY-TYPE-PERSON}
   {:filter-key :places
    :title-key :category/places
    :items-key :places
    :filter-state-key :shared/filter-places
    :category-type state/CATEGORY-TYPE-PLACE}
   {:filter-key :projects
    :title-key :category/projects
    :items-key :projects
    :filter-state-key :shared/filter-projects
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
    [task-item/category-selector task state/CATEGORY-TYPE-GOAL goals (t :category/goal)]
    [relation-badges/relation-badges-expanded (:relations task) "tsk" (:id task)]]
   [:div.item-actions
    (when (and (not= 1 (:today task))
              (nil? (:due_date task))
              (not= 1 (:done task)))
      [:button.link-today-btn
       {:on-click (fn [e]
                    (.stopPropagation e)
                    (state/set-task-today (:id task) true))
        :title (t :task/link-today)}
       "←"])
    [task-item/task-attribute-selectors task]
    [task-item/task-combined-action-button task]]])

(defn- task-header [task is-expanded done-mode? due-date-mode? manual-mode?]
  [:div.item-header
   {:on-click #(state/toggle-expanded :tasks-page/expanded-task (:id task))}
   [:div.item-title
    [relation-link/relation-link-button :task (:id task)]
    (when (seq (:due_time task))
      [:span.task-time (:due_time task)])
    (:title task)
    (when is-expanded
      [:<>
       [:button.edit-icon {:on-click (fn [e]
                                       (.stopPropagation e)
                                       (state/set-editing-modal :task task))}
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
        [:span.due-date {:class (when overdue? "overdue")
                         :data-tooltip (date/get-day-name (:due_date task))}
         (date/format-date-localized (:due_date task))]))
    (when (and (not due-date-mode?) (not manual-mode?))
      [:span {:data-tooltip (some-> (:modified_at task) (.substring 0 10) date/get-day-name)}
       (date/format-datetime-localized (:modified_at task))])]])

(defn- task-item-content [task is-expanded people places projects goals done-mode? due-date-mode? manual-mode?]
  [:div
   [task-header task is-expanded done-mode? due-date-mode? manual-mode?]
   (if is-expanded
     [task-expanded-details task people places projects goals]
     [task-item/task-categories-readonly task])])

(defn tasks-list []
  (let [{:keys [people places projects goals tasks-page/expanded-task sort-mode drag-task drag-over-task]} @state/*app-state
        tasks (state/filtered-tasks)
        manual-mode? (= sort-mode :manual)
        due-date-mode? (= sort-mode :due-date)
        done-mode? (= sort-mode :done)
        any-task-open? (some? expanded-task)
        drag-enabled? (and manual-mode? (not any-task-open?))]
    (into [:ul.items]
      (for [task tasks]
        (let [is-expanded (= expanded-task (:id task))
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
           [task-item-content task is-expanded people places projects goals done-mode? due-date-mode? manual-mode?]])))))

(defn- recurring-toggle []
  (let [recurring-mode (:tasks-page/recurring-mode @state/*app-state)]
    [:div.series-mode-toggle.toggle-group
     [:button {:class (when recurring-mode "active")
               :on-click #(state/toggle-recurring-mode)}
      (t :tasks/recurring)]]))

(defn- rtask-scope-selector [rtask]
  (let [scope (or (:scope rtask) "both")]
    [:div.task-scope-selector.toggle-group.compact
     (for [s ["private" "both" "work"]]
       ^{:key s}
       [:button.toggle-option
        {:class (when (= scope s) "active")
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-recurring-task-scope (:id rtask) s))}
        s])]))

(defn- rtask-category-selector [rtask category-type entities label]
  (let [current-categories (case category-type
                             state/CATEGORY-TYPE-PERSON (:people rtask)
                             state/CATEGORY-TYPE-PLACE (:places rtask)
                             state/CATEGORY-TYPE-PROJECT (:projects rtask)
                             state/CATEGORY-TYPE-GOAL (:goals rtask)
                             [])]
    [category-selector/category-selector
     {:entity rtask
      :entity-id-key :id
      :category-type category-type
      :entities entities
      :label label
      :current-categories current-categories
      :on-categorize #(state/categorize-recurring-task (:id rtask) category-type %)
      :on-uncategorize #(state/uncategorize-recurring-task (:id rtask) category-type %)
      :on-close-focus-fn nil
      :open-selector-state (:category-selector/open @state/*app-state)
      :search-state (:category-selector/search @state/*app-state)
      :open-selector-fn state/open-category-selector
      :close-selector-fn state/close-category-selector
      :set-search-fn state/set-category-selector-search}]))

(defn- rtask-expanded-view [rtask people places projects goals]
  [:div.item-details
   (when (seq (:description rtask))
     [:div.item-description [task-item/markdown (:description rtask)]])
   [:div.item-tags
    [rtask-category-selector rtask state/CATEGORY-TYPE-PERSON people (t :category/person)]
    [rtask-category-selector rtask state/CATEGORY-TYPE-PLACE places (t :category/place)]
    [rtask-category-selector rtask state/CATEGORY-TYPE-PROJECT projects (t :category/project)]
    [rtask-category-selector rtask state/CATEGORY-TYPE-GOAL goals (t :category/goal)]]
   [:div.item-actions
    [rtask-scope-selector rtask]
    [:div.combined-button-wrapper
     [:button.delete-btn {:on-click #(state/set-confirm-delete-rtask rtask)}
      (t :task/delete)]]]])

(defn- rtask-header [rtask is-expanded]
  [:div.item-header
   {:on-click #(state/set-expanded-rtask (when-not is-expanded (:id rtask)))}
   [:div.item-title
    (:title rtask)
    (when is-expanded
      [:button.edit-icon {:on-click (fn [e]
                                      (.stopPropagation e)
                                      (state/set-editing-modal :recurring-task rtask))}
       "✎"])]])

(defn- rtask-categories-readonly [rtask]
  [:div.item-tags-readonly
   [task-item/category-badges
    {:item rtask
     :category-types [[state/CATEGORY-TYPE-PERSON :people]
                      [state/CATEGORY-TYPE-PLACE :places]
                      [state/CATEGORY-TYPE-PROJECT :projects]
                      [state/CATEGORY-TYPE-GOAL :goals]]
     :toggle-fn state/toggle-shared-filter
     :has-filter-fn state/has-filter-for-type?}]])

(defn- rtask-create-task-button [rtask]
  (let [action (state/next-recurring-task-action rtask)
        enabled? (and action (not= (:action action) :none))
        disabled-reason (when-not enabled?
                          (if (and action (= (:action action) :none))
                            (t :tasks/create-next-disabled-future)
                            (t :tasks/create-next-disabled-no-schedule)))]
    [:button.create-next-meeting-btn
     (cond-> {:class (when-not enabled? "disabled")
              :disabled (not enabled?)
              :on-click (fn [e]
                          (.stopPropagation e)
                          (when enabled?
                            (state/create-task-for-recurring (:id rtask) (:date action) (:time action))))}
       disabled-reason (assoc :title disabled-reason))
     (t :tasks/create-next-task)]))

(defn- rtask-item [rtask expanded-id people places projects goals]
  (let [is-expanded (= expanded-id (:id rtask))]
    [:li {:class (when is-expanded "expanded")}
     [rtask-header rtask is-expanded]
     (if is-expanded
       [rtask-expanded-view rtask people places projects goals]
       [:<>
        [rtask-categories-readonly rtask]
        [rtask-create-task-button rtask]])]))

(defn- recurring-search-add-form []
  (let [input-value (:filter-search @recurring-tasks-state/*recurring-tasks-page-state)]
    [:div.combined-search-add-form
     [:input#tasks-filter-search
      {:type "text"
       :auto-complete "off"
       :placeholder (t :tasks/search-or-add-recurring)
       :value input-value
       :on-change #(state/set-recurring-task-filter-search (-> % .-target .-value))
       :on-key-down (fn [e]
                      (cond
                        (and (.-altKey e) (= (.-key e) "Enter") (seq input-value))
                        (do
                          (.preventDefault e)
                          (state/add-recurring-task input-value
                                                    #(state/set-recurring-task-filter-search "")))

                        (= (.-key e) "Escape")
                        (state/set-recurring-task-filter-search "")))}]
     [:button {:on-click #(when (seq input-value)
                            (state/add-recurring-task input-value
                                                      (fn [] (state/set-recurring-task-filter-search ""))))}
      (t :tasks/add-button)]
     (when (seq input-value)
       [:button.clear-search {:on-click #(state/set-recurring-task-filter-search "")} "x"])]))

(defn- recurring-tasks-list []
  (let [{:keys [recurring-tasks people places projects goals]} @state/*app-state
        {:keys [expanded-rtask]} @recurring-tasks-state/*recurring-tasks-page-state]
    (if (empty? recurring-tasks)
      [:p.empty-message (t :tasks/no-recurring)]
      [:ul.items
       (for [rtask recurring-tasks]
         ^{:key (:id rtask)}
         [rtask-item rtask expanded-rtask people places projects goals])])))
