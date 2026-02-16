(ns et.tr.ui.views.today
  (:require [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.state.meets :as meets-state]
            [et.tr.ui.date :as date]
            [et.tr.ui.components.drag-drop :as drag-drop]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.ui.components.filter-section :as filter-section]
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

(defn today-task-item [task & {:keys [show-day-prefix overdue? hide-date] :or {show-day-prefix false overdue? false hide-date false}}]
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
      (when-not hide-date
        [:span.task-date {:data-tooltip (date/get-day-name (:due_date task))}
         (date/format-date-localized (:due_date task))])]
     (when is-expanded
       [today-task-expanded-details task])]))

(defn- today-meet-expanded-details [meet]
  [:div.today-task-details
   (when (seq (:description meet))
     [:div.item-description [task-item/markdown (:description meet)]])
   (when (or (seq (:people meet)) (seq (:places meet)) (seq (:projects meet)))
     [:div.item-categories
      (for [person (:people meet)]
        ^{:key (str "person-" (:id person))}
        [:span.category-tag.person (:name person)])
      (for [place (:places meet)]
        ^{:key (str "place-" (:id place))}
        [:span.category-tag.place (:name place)])
      (for [project (:projects meet)]
        ^{:key (str "project-" (:id project))}
        [:span.category-tag.project (:name project)])])])

(defn- today-meet-item [meet & {:keys [show-day-prefix hide-date] :or {show-day-prefix false hide-date false}}]
  (let [show-prefix? (and show-day-prefix (date/within-days? (:start_date meet) 6))
        expanded-meet (:today-page/expanded-meet @state/*app-state)
        is-expanded (= expanded-meet (:id meet))]
    [:div.today-task-item.meet-item {:class (when is-expanded "expanded")}
     [:div.today-task-header
      {:on-click #(state/toggle-expanded :today-page/expanded-meet (:id meet))}
      [:div.today-task-content
       [:span.task-title
        (when show-prefix?
          [:span.task-day-prefix (str (date/get-day-name (:start_date meet))
                                      (when (seq (:start_time meet)) ","))])
        (when (seq (:start_time meet))
          [:span.task-time (:start_time meet)])
        (:title meet)]
       (when-not is-expanded
         (when (or (seq (:people meet)) (seq (:places meet)) (seq (:projects meet)))
           [:div.item-categories
            (for [person (:people meet)]
              ^{:key (str "person-" (:id person))}
              [:span.category-tag.person (:name person)])
            (for [place (:places meet)]
              ^{:key (str "place-" (:id place))}
              [:span.category-tag.place (:name place)])
            (for [project (:projects meet)]
              ^{:key (str "project-" (:id project))}
              [:span.category-tag.project (:name project)])]))]
      (when-not hide-date
        [:span.task-date {:data-tooltip (date/get-day-name (:start_date meet))}
         (date/format-date-localized (:start_date meet))])]
     (when is-expanded
       [today-meet-expanded-details meet])]))

(defn- interleave-by-date [tasks meets]
  (sort-by (fn [item]
             [(or (:due_date item) (:start_date item))
              (if (or (:due_time item) (:start_time item)) 1 0)
              (or (:due_time item) (:start_time item) "")])
           (concat (map #(assoc % :item-type :task) tasks)
                   (map #(assoc % :item-type :meet) meets))))

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


(defn- today-exclusion-filter-section [filter-key items excluded-ids collapsed-filters toggle-fn clear-fn]
  [filter-section/category-filter-section {:title (t (keyword "category" (name filter-key)))
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
                            :section-class (str (name filter-key) " exclusion-filter")
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

(defn- task-list-section [tasks & {:keys [overdue? show-day-prefix hide-date]
                                      :or {overdue? false show-day-prefix false hide-date false}}]
  [:div.task-list
   (doall
    (for [task tasks]
      ^{:key (:id task)}
      [today-task-item task
       :overdue? overdue?
       :show-day-prefix show-day-prefix
       :hide-date hide-date]))])

(defn- today-overdue-section [overdue]
  (when (seq overdue)
    [:div.today-section.overdue
     [:h3 (t :today/overdue)]
     [task-list-section overdue :overdue? true]]))

(defn- today-today-section [today-tasks today-meets]
  (let [items (interleave-by-date today-tasks today-meets)]
    [:div.today-section.today
     [:h3 (date/today-formatted)]
     (if (seq items)
       [:div.task-list
        (doall
         (for [item items]
           (if (= (:item-type item) :meet)
             ^{:key (str "meet-" (:id item))}
             [today-meet-item item :hide-date true]
             ^{:key (str "task-" (:id item))}
             [today-task-item item :hide-date true])))]
       [:p.empty-message (t :today/no-today)])]))

(defn- find-task-by-id [task-id]
  (first (filter #(= (:id %) task-id) (:tasks @state/*app-state))))

(defn- ensure-urgency [task-id target-urgency]
  (let [current-urgency (:urgency (find-task-by-id task-id))]
    (when (not= current-urgency target-urgency)
      (state/set-task-urgency task-id target-urgency))))

(defn- draggable-urgent-task-item [task target-urgency drag-enabled?]
  (let [drag-task (:drag-task @state/*app-state)
        drag-over-task (:drag-over-task @state/*app-state)
        is-dragging (= drag-task (:id task))
        is-drag-over (= drag-over-task (:id task))]
    [:div.draggable-urgent-task
     {:class (str (when is-dragging "dragging")
                  (when is-drag-over " drag-over")
                  (when-not drag-enabled? " drag-disabled"))
      :draggable drag-enabled?
      :on-drag-start (drag-drop/make-drag-start-handler task state/set-drag-task drag-enabled?)
      :on-drag-end (fn [_] (state/clear-drag-state))
      :on-drag-over (drag-drop/make-drag-over-handler task state/set-drag-over-task drag-enabled?)
      :on-drag-leave (drag-drop/make-drag-leave-handler drag-over-task task #(state/set-drag-over-task nil))
      :on-drop (drag-drop/make-urgency-task-drop-handler drag-task task target-urgency ensure-urgency state/reorder-task drag-enabled?)}
     [today-task-item task]]))

(defn- urgency-task-list [tasks target-urgency drag-enabled?]
  (let [drag-task (:drag-task @state/*app-state)
        drag-over-section (:drag-over-urgency-section @state/*app-state)
        is-section-drag-over (= drag-over-section target-urgency)]
    [:div.urgency-task-list
     {:class (str (when is-section-drag-over "section-drag-over")
                  (when-not drag-enabled? " drag-disabled"))
      :on-drag-over (fn [e]
                      (when drag-enabled?
                        (.preventDefault e)
                        (state/set-drag-over-urgency-section target-urgency)))
      :on-drag-leave (fn [e]
                       (when (= (.-target e) (.-currentTarget e))
                         (state/set-drag-over-urgency-section nil)))
      :on-drop (drag-drop/make-urgency-section-drop-handler drag-task tasks target-urgency ensure-urgency state/reorder-task state/clear-drag-state drag-enabled?)}
     (if (seq tasks)
       (doall
        (for [task tasks]
          ^{:key (:id task)}
          [draggable-urgent-task-item task target-urgency drag-enabled?]))
       [:p.empty-urgency-message (t :today/no-tasks-in-section)])]))

(defn- today-urgent-section [superurgent urgent]
  (let [expanded-task (:today-page/expanded-task @state/*app-state)
        editing-task (:editing-task @state/*app-state)
        any-task-open? (or expanded-task editing-task)
        drag-enabled? (not any-task-open?)]
    [:div.today-section.urgent
     [:h3 (t :today/urgent-matters)]
     [:div.urgency-subsection.superurgent
      [:h4 "🚨🚨"]
      [urgency-task-list superurgent "superurgent" drag-enabled?]]
     [:div.urgency-subsection.urgent
      [:h4 "🚨"]
      [urgency-task-list urgent "urgent" drag-enabled?]]]))

(defn- today-upcoming-section [upcoming-tasks upcoming-meets]
  (let [items (interleave-by-date upcoming-tasks upcoming-meets)]
    [:div.today-section.upcoming
     [:div.section-header
      [:h3 (t :today/upcoming)]
      [horizon-selector]]
     (if (seq items)
       [:div.task-list
        (doall
         (for [item items]
           (if (= (:item-type item) :meet)
             ^{:key (str "meet-" (:id item))}
             [today-meet-item item :show-day-prefix true]
             ^{:key (str "task-" (:id item))}
             [today-task-item item :show-day-prefix true])))]
       [:p.empty-message (t :today/no-upcoming)])]))

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
        today-m (state/today-meets)
        superurgent (state/superurgent-tasks)
        urgent (state/urgent-tasks)
        upcoming (state/upcoming-tasks)
        upcoming-m (state/upcoming-meets)
        selected-view (:today-page/selected-view @state/*app-state)]
    [:div.main-layout
     [today-sidebar-filters]
     [:div.main-content.today-content
      [today-overdue-section overdue]
      [today-today-section today today-m]
      [today-view-switcher]
      (when (= selected-view :urgent)
        [today-urgent-section superurgent urgent])
      (when (= selected-view :upcoming)
        [today-upcoming-section upcoming upcoming-m])]]))
