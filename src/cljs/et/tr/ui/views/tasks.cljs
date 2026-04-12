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
      (and (= (.-key e) "Enter") (not done-mode?))
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
     [sort-mode-button sort-mode :recent :tasks/sort-recent]
     [sort-mode-button sort-mode :manual :tasks/sort-manual]
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


(defn- send-to-day-option [task-id offset target-date]
  [:button.send-to-day-option
   {:key offset
    :on-click (fn [e]
                (.stopPropagation e)
                (if (zero? offset)
                  (state/set-task-today task-id true)
                  (state/set-task-lined-up-for task-id target-date))
                (swap! state/*app-state assoc :tasks-page/send-to-day-open nil))}
   (if (zero? offset) (t :today/today) (date/get-day-label target-date))])

(defn- send-to-day-unassign [task]
  [:button.send-to-day-option.unassign-option
   {:on-click (fn [e]
                (.stopPropagation e)
                (if (= 1 (:today task))
                  (state/set-task-today (:id task) false)
                  (state/set-task-lined-up-for (:id task) nil))
                (swap! state/*app-state assoc :tasks-page/send-to-day-open nil))}
   (t :task/unassign-day)])

(defn- send-to-day-dropdown [task assigned?]
  (let [today (date/today-str)]
    (into [:div.send-to-day-dropdown]
      (concat
        (for [offset (range 5)]
          [send-to-day-option (:id task) offset (date/add-days today offset)])
        (when assigned?
          [[send-to-day-unassign task]])))))

(defn- send-to-day-selector [task]
  (when (and (nil? (:due_date task))
             (not= 1 (:done task)))
    (let [is-open (= (:tasks-page/send-to-day-open @state/*app-state) (:id task))
          assigned? (or (= 1 (:today task)) (:lined_up_for task))
          btn-label (cond
                      (= 1 (:today task)) (t :today/today)
                      (:lined_up_for task) (date/get-day-label (:lined_up_for task))
                      :else "←")]
      [:div.send-to-day-wrapper
       [:button.link-today-btn
        {:class (when assigned? "assigned")
         :on-click (fn [e]
                     (.stopPropagation e)
                     (swap! state/*app-state assoc :tasks-page/send-to-day-open
                            (when-not is-open (:id task))))}
        btn-label]
       (when is-open
         [send-to-day-dropdown task assigned?])])))

(defn- task-expanded-details [task people places projects goals]
  [:div.item-details
   (when (seq (:description task))
     [task-item/clampable-description
      {:text (:description task)
       :on-click #(state/set-editing-modal :task task)}])
   [:div.item-tags
    [task-item/category-selector task state/CATEGORY-TYPE-PERSON people (t :category/person)]
    [task-item/category-selector task state/CATEGORY-TYPE-PLACE places (t :category/place)]
    [task-item/category-selector task state/CATEGORY-TYPE-PROJECT projects (t :category/project)]
    [task-item/category-selector task state/CATEGORY-TYPE-GOAL goals (t :category/goal)]
    [relation-badges/relation-badges-expanded (:relations task) "tsk" (:id task)]]
   [:div.item-actions
    [send-to-day-selector task]
    [task-item/task-attribute-selectors task]
    [task-item/task-combined-action-button task]]])

(defn- task-inline-title-edit [task]
  (let [value (or (:tasks-page/inline-edit-title @state/*app-state) "")]
    [:input.inline-title-edit
     {:type "text"
      :auto-focus true
      :value value
      :on-click #(.stopPropagation %)
      :on-change #(swap! state/*app-state assoc :tasks-page/inline-edit-title (.. % -target -value))
      :on-key-down (fn [e]
                     (case (.-key e)
                       "Enter" (do (.stopPropagation e)
                                   (state/update-task (:id task) value (:description task) (:tags task)
                                     #(swap! state/*app-state dissoc :tasks-page/inline-edit-task :tasks-page/inline-edit-title)))
                       "Escape" (do (.stopPropagation e)
                                    (swap! state/*app-state dissoc :tasks-page/inline-edit-task :tasks-page/inline-edit-title))
                       nil))
      :on-blur (fn [_]
                 (state/update-task (:id task) value (:description task) (:tags task)
                   #(swap! state/*app-state dissoc :tasks-page/inline-edit-task :tasks-page/inline-edit-title)))}]))

(defn- task-header [task is-expanded done-mode? due-date-mode? manual-mode?]
  (let [inline-editing? (= (:tasks-page/inline-edit-task @state/*app-state) (:id task))]
    [:div.item-header
     {:on-click (fn [e]
                  (when-not (or inline-editing?
                                (and is-expanded (not (.. js/window getSelection -isCollapsed))))
                    (state/toggle-expanded :tasks-page/expanded-task (:id task))))}
     [:div.item-title
      [relation-link/relation-link-button :task (:id task)]
      (if inline-editing?
        [task-inline-title-edit task]
        [:span.item-title-text
         {:on-click (fn [e]
                      (when (and is-expanded (.-altKey e))
                        (.stopPropagation e)
                        (swap! state/*app-state assoc
                          :tasks-page/inline-edit-task (:id task)
                          :tasks-page/inline-edit-title (:title task))))}
         (:title task)])]
   (when is-expanded
     [:div.item-toolbar
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
        [task-item/time-picker task :show-clear? true])])
   [:div.item-date
    (cond
      (and (:due_date task) done-mode?)
      [:span.due-date (str (date/format-date-localized (:due_date task))
                           (when (seq (:due_time task))
                             (str " - " (:due_time task))))]

      (and (:due_date task) (not done-mode?))
      (let [today (date/today-str)
            overdue? (< (:due_date task) today)]
        [:span.due-date {:class (when overdue? "overdue")
                         :data-tooltip (date/get-day-name (:due_date task))}
         (str (date/format-date-localized (:due_date task))
              (when (seq (:due_time task))
                (str " - " (:due_time task))))])

      (and (not done-mode?) (not is-expanded) (= 1 (:today task)))
      [:span.assigned-day (t :today/today)]

      (and (not done-mode?) (not is-expanded) (:lined_up_for task))
      [:span.assigned-day (date/get-day-label (:lined_up_for task))])
    (when (and (:recurring_task_id task) (not= (:id (state/recurring-filter)) (:recurring_task_id task)))
      [:span.recurrence-icon {:on-click (fn [e]
                                          (.stopPropagation e)
                                          (state/set-recurring-filter {:id (:recurring_task_id task) :title (:title task)}))}
       "🔁"])
    (when (or (:reminder_date task) (= "active" (:reminder task)))
      [:span.reminder-icon "🔔"])]]))

(defn- task-item-content [task is-expanded people places projects goals done-mode? due-date-mode? manual-mode?]
  [:div
   [task-header task is-expanded done-mode? due-date-mode? manual-mode?]
   (if is-expanded
     [task-expanded-details task people places projects goals]
     [task-item/task-categories-readonly task])])

(defn- extract-date [modified-at]
  (when modified-at
    (.substring modified-at 0 10)))

(defn tasks-list []
  (let [{:keys [people places projects goals tasks-page/expanded-task sort-mode drag-task drag-over-task]} @state/*app-state
        tasks (state/filtered-tasks)
        manual-mode? (= sort-mode :manual)
        due-date-mode? (= sort-mode :due-date)
        done-mode? (= sort-mode :done)
        any-task-open? (some? expanded-task)
        drag-enabled? (and manual-mode? (not any-task-open?))
        render-task (fn [task]
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
                         [task-item-content task is-expanded people places projects goals done-mode? due-date-mode? manual-mode?]]))]
    (if done-mode?
      (let [grouped (group-by #(extract-date (or (:done_at %) (:modified_at %))) tasks)
            sorted-dates (->> (keys grouped) (sort #(compare %2 %1)))]
        (into [:div.done-tasks]
          (for [d sorted-dates]
            ^{:key d}
            [:div.done-day-group
             [:h4.done-day-header (date/day-formatted d)]
             (into [:ul.items] (map render-task (get grouped d)))])))
      (into [:ul.items] (map render-task tasks)))))

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
     [task-item/clampable-description
      {:text (:description rtask)
       :on-click #(state/set-editing-modal :recurring-task rtask)}])
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

(defn- rtask-inline-title-edit [rtask]
  (let [value (or (:rtasks-page/inline-edit-title @state/*app-state) "")]
    [:input.inline-title-edit
     {:type "text"
      :auto-focus true
      :value value
      :on-click #(.stopPropagation %)
      :on-change #(swap! state/*app-state assoc :rtasks-page/inline-edit-title (.. % -target -value))
      :on-key-down (fn [e]
                     (case (.-key e)
                       "Enter" (do (.stopPropagation e)
                                   (state/update-recurring-task (:id rtask) value (:description rtask) (:tags rtask)
                                     #(swap! state/*app-state dissoc :rtasks-page/inline-edit-rtask :rtasks-page/inline-edit-title)))
                       "Escape" (do (.stopPropagation e)
                                    (swap! state/*app-state dissoc :rtasks-page/inline-edit-rtask :rtasks-page/inline-edit-title))
                       nil))
      :on-blur (fn [_]
                 (state/update-recurring-task (:id rtask) value (:description rtask) (:tags rtask)
                   #(swap! state/*app-state dissoc :rtasks-page/inline-edit-rtask :rtasks-page/inline-edit-title)))}]))

(defn- rtask-header [rtask is-expanded]
  (let [inline-editing? (= (:rtasks-page/inline-edit-rtask @state/*app-state) (:id rtask))]
    [:div.item-header
     {:on-click (fn [_]
                  (when-not (or inline-editing?
                                (and is-expanded (not (.. js/window getSelection -isCollapsed))))
                    (state/set-expanded-rtask (when-not is-expanded (:id rtask)))))}
     [:div.item-title
      (if inline-editing?
        [rtask-inline-title-edit rtask]
        [:span.item-title-text
         {:on-click (fn [e]
                      (when (and is-expanded (.-altKey e))
                        (.stopPropagation e)
                        (swap! state/*app-state assoc
                          :rtasks-page/inline-edit-rtask (:id rtask)
                          :rtasks-page/inline-edit-title (:title rtask))))}
         (:title rtask)])]
     (when is-expanded
       [:div.item-toolbar
        [:button.edit-icon {:on-click (fn [e]
                                        (.stopPropagation e)
                                        (state/set-editing-modal :recurring-task rtask))}
         "✎"]])
     [:button.series-filter-btn {:on-click (fn [e]
                                             (.stopPropagation e)
                                             (state/set-recurring-filter rtask))
                                  :title (t :tasks/filter-by-recurring)}
      "⏚"]]))

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
  (let [has-schedule? (and (:schedule_days rtask) (not= (:schedule_days rtask) ""))]
    [:button.create-next-meeting-btn
     (cond-> {:class (when-not has-schedule? "disabled")
              :disabled (not has-schedule?)
              :on-click (fn [e]
                          (.stopPropagation e)
                          (when has-schedule?
                            (state/open-create-date-modal :recurring-task rtask)))}
       (not has-schedule?) (assoc :title (t :tasks/create-next-disabled-no-schedule)))
     (t :tasks/create-task)]))

(defn- rtask-item [rtask expanded-id people places projects goals]
  (let [is-expanded (= expanded-id (:id rtask))]
    [:li {:class (when is-expanded "expanded")}
     [rtask-header rtask is-expanded]
     (if is-expanded
       [rtask-expanded-view rtask people places projects goals]
       [:<>
        [rtask-categories-readonly rtask]
        [rtask-create-task-button rtask]])]))

(defn recurring-filter-bar []
  (let [recurring-filter (state/recurring-filter)]
    [:div.series-filter-bar
     [:span.series-filter-label (:title recurring-filter)]
     [:button.clear-search {:on-click #(state/clear-recurring-filter)} "x"]]))

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
