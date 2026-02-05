(ns et.tr.ui.views.today
  (:require [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.state.tasks-page :as tasks-page]
            [et.tr.ui.date :as date]
            [et.tr.ui.components.drag-drop :as drag-drop]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.i18n :refer [t]]))

(def ^:private today-category-shortcut-keys
  {"Digit1" :places
   "Digit2" :projects})

(def today-category-shortcut-numbers
  (into {} (map (fn [[k v]] [v (subs k 5)]) today-category-shortcut-keys)))

(defn get-today-category-shortcut-keys []
  today-category-shortcut-keys)

(defn- today-task-edit-form [task]
  (let [title (r/atom (:title task))
        description (r/atom (or (:description task) ""))]
    (fn []
      [task-item/item-edit-form
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
  (let [editing-task (:editing-task @state/*app-state)
        is-editing (= editing-task (:id task))]
    (if is-editing
      [today-task-edit-form task]
      [:div.today-task-details
       (when (seq (:description task))
         [:div.item-description [task-item/markdown (:description task)]])
       [task-item/task-category-badges task]
       [:div.item-actions
        [task-item/task-attribute-selectors task]
        [task-item/task-combined-action-button task]]])))

(defn today-task-item [task & {:keys [show-day-of-week show-day-prefix overdue?] :or {show-day-of-week false show-day-prefix false overdue? false}}]
  (let [show-prefix? (and show-day-prefix (date/within-days? (:due_date task) 6))
        expanded-task (:today-page/expanded-task @state/*app-state)
        editing-task (:editing-task @state/*app-state)
        is-expanded (= expanded-task (:id task))
        is-editing (= editing-task (:id task))]
    [:div.today-task-item {:class (when is-expanded "expanded")}
     [:div.today-task-header
      {:on-click #(state/toggle-expanded :today-page/expanded-task (:id task))}
      [:div.today-task-content
       [:span.task-title
        (when show-prefix?
          [:span.task-day-prefix (str (date/get-day-name (:due_date task))
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
          [task-item/time-picker task])]
       (when-not is-expanded
         [task-item/task-category-badges task])]
      [:span.task-date
       (cond
         show-prefix? (date/format-date-localized (:due_date task))
         show-day-of-week (date/format-date-with-day (:due_date task))
         :else (date/format-date-localized (:due_date task)))]]
     (when is-expanded
       [today-task-expanded-details task])]))

(defn horizon-selector []
  (let [horizon (:upcoming-horizon @state/*app-state)]
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
        search-term (get-in @state/*app-state search-state-path "")
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

(defn- today-exclusion-filter-section [filter-key items excluded-ids collapsed-filters toggle-fn clear-fn]
  [category-filter-section {:title (t (keyword "category" (name filter-key)))
                            :shortcut-number (today-category-shortcut-numbers filter-key)
                            :filter-key filter-key
                            :items items
                            :marked-ids excluded-ids
                            :toggle-fn toggle-fn
                            :clear-fn clear-fn
                            :collapsed? (contains? collapsed-filters filter-key)
                            :toggle-collapsed-fn state/toggle-today-filter-collapsed
                            :set-search-fn state/set-today-category-search
                            :search-state-path [:today-page/category-search filter-key]
                            :section-class "exclusion-filter"
                            :item-active-class "excluded"
                            :label-class "excluded"
                            :page-prefix "today"}])

(defn today-sidebar-filters []
  (let [{:keys [places projects]} @state/*app-state
        excluded-places (:today-page/excluded-places @state/*app-state)
        excluded-projects (:today-page/excluded-projects @state/*app-state)
        collapsed-filters (:today-page/collapsed-filters @state/*app-state)]
    [:div.sidebar
     [today-exclusion-filter-section :places places excluded-places collapsed-filters
      state/toggle-today-excluded-place state/clear-today-excluded-places]
     [today-exclusion-filter-section :projects projects excluded-projects collapsed-filters
      state/toggle-today-excluded-project state/clear-today-excluded-projects]]))

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
   [:h3 (date/today-formatted)]
   (if (seq today)
     [task-list-section today]
     [:p.empty-message (t :today/no-today)])])

(defn- draggable-urgent-task-item [task target-urgency]
  (let [drag-task (:drag-task @state/*app-state)
        drag-over-task (:drag-over-task @state/*app-state)
        is-dragging (= drag-task (:id task))
        is-drag-over (= drag-over-task (:id task))]
    [:div.draggable-urgent-task
     {:class (str (when is-dragging "dragging")
                  (when is-drag-over " drag-over"))
      :draggable true
      :on-drag-start (drag-drop/make-drag-start-handler task state/set-drag-task)
      :on-drag-end (fn [_] (state/clear-drag-state))
      :on-drag-over (drag-drop/make-drag-over-handler task state/set-drag-over-task)
      :on-drag-leave (drag-drop/make-drag-leave-handler drag-over-task task #(state/set-drag-over-task nil))
      :on-drop (fn [e]
                 (.preventDefault e)
                 (let [current-drag-task (:drag-task @state/*app-state)]
                   (when (and current-drag-task (not= current-drag-task (:id task)))
                     (let [rect (.getBoundingClientRect (.-currentTarget e))
                           y (.-clientY e)
                           mid-y (+ (.-top rect) (/ (.-height rect) 2))
                           position (if (< y mid-y) "before" "after")
                           dragged-task (first (filter #(= (:id %) current-drag-task) (:tasks @state/*app-state)))
                           current-urgency (:urgency dragged-task)]
                       (when (not= current-urgency target-urgency)
                         (state/set-task-urgency current-drag-task target-urgency))
                       (state/reorder-task current-drag-task (:id task) position)))))}
     [today-task-item task :show-day-of-week true]]))

(defn- urgency-task-list [tasks target-urgency]
  (let [drag-over-section (:drag-over-urgency-section @state/*app-state)
        is-section-drag-over (= drag-over-section target-urgency)]
    [:div.urgency-task-list
     {:class (when is-section-drag-over "section-drag-over")
      :on-drag-over (fn [e]
                      (.preventDefault e)
                      (state/set-drag-over-urgency-section target-urgency))
      :on-drag-leave (fn [e]
                       (when (= (.-target e) (.-currentTarget e))
                         (state/set-drag-over-urgency-section nil)))
      :on-drop (fn [e]
                 (.preventDefault e)
                 (let [drag-task-id (:drag-task @state/*app-state)]
                   (when drag-task-id
                     (let [dragged-task (first (filter #(= (:id %) drag-task-id) (:tasks @state/*app-state)))
                           current-urgency (:urgency dragged-task)]
                       (when (not= current-urgency target-urgency)
                         (state/set-task-urgency drag-task-id target-urgency))
                       (when-let [last-task (last tasks)]
                         (when (not= (:id last-task) drag-task-id)
                           (state/reorder-task drag-task-id (:id last-task) "after")))
                       (state/clear-drag-state)))))}
     (if (seq tasks)
       (doall
        (for [task tasks]
          ^{:key (:id task)}
          [draggable-urgent-task-item task target-urgency]))
       [:p.empty-urgency-message (t :today/no-tasks-in-section)])]))

(defn- today-urgent-section [superurgent urgent]
  [:div.today-section.urgent
   [:h3 (t :today/urgent-matters)]
   [:div.urgency-subsection.superurgent
    [:h4 "🚨🚨"]
    [urgency-task-list superurgent "superurgent"]]
   [:div.urgency-subsection.urgent
    [:h4 "🚨"]
    [urgency-task-list urgent "urgent"]]])

(defn- today-upcoming-section [upcoming]
  [:div.today-section.upcoming
   [:div.section-header
    [:h3 (t :today/upcoming)]
    [horizon-selector]]
   (if (seq upcoming)
     [task-list-section upcoming :show-day-of-week true :show-day-prefix true]
     [:p.empty-message (t :today/no-upcoming)])])

(defn- today-view-switcher []
  (let [selected-view (:today-page/selected-view @state/*app-state)]
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
        superurgent (state/superurgent-tasks)
        urgent (state/urgent-tasks)
        upcoming (state/upcoming-tasks)
        selected-view (:today-page/selected-view @state/*app-state)]
    [:div.main-layout
     [today-sidebar-filters]
     [:div.main-content.today-content
      [today-overdue-section overdue]
      [today-today-section today]
      [today-view-switcher]
      (when (= selected-view :urgent)
        [today-urgent-section superurgent urgent])
      (when (= selected-view :upcoming)
        [today-upcoming-section upcoming])]]))
