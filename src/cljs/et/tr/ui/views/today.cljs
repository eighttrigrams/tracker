(ns et.tr.ui.views.today
  (:require [clojure.string]
            [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.date :as date]
            [et.tr.ui.components.drag-drop :as drag-drop]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.ui.components.filter-section :as filter-section]
            [et.tr.filters :as filters]
            [et.tr.i18n :refer [t]]))

(def ^:private today-category-shortcut-keys
  {"Digit1" :places
   "Digit2" :projects})

(def today-category-shortcut-numbers
  (into {} (map (fn [[k v]] [v (subs k 5)]) today-category-shortcut-keys)))

(defn get-today-category-shortcut-keys []
  today-category-shortcut-keys)

(defn- today-task-expanded-details [task & {:keys [show-unlink?]}]
  [:div.today-task-details
   (when (seq (:description task))
     [:div.item-description [task-item/markdown (:description task)]])
   [task-item/task-category-badges task]
   [:div.item-actions
    [task-item/task-attribute-selectors task]
    [task-item/task-combined-action-button task
     :extra-dropdown-items
     (when show-unlink?
       [:button.dropdown-item.unlink-today
        {:on-click #(let [selected-day (or (:today-page/selected-day @state/*app-state) 0)]
                      (state/set-task-dropdown-open nil)
                      (if (zero? selected-day)
                        (state/set-task-today (:id task) false)
                        (state/set-task-lined-up-for (:id task) nil)))}
        (t :task/unlink-today)])]]])

(defn today-task-item [task & {:keys [show-day-prefix overdue? hide-date emoji-prefix show-unlink?] :or {show-day-prefix false overdue? false hide-date false}}]
  (let [show-prefix? (and show-day-prefix (date/within-days? (:due_date task) 6))
        expanded-task (:today-page/expanded-task @state/*app-state)
        is-expanded (= expanded-task (:id task))]
    [:div.today-task-item {:class (when is-expanded "expanded")}
     [:div.today-task-header
      {:on-click #(state/toggle-expanded :today-page/expanded-task (:id task))}
      [:div.today-task-content
       [:span.task-title
        (when emoji-prefix
          [:span.task-emoji-prefix emoji-prefix])
        (when show-prefix?
          [:span.task-day-prefix (str (date/get-day-name (:due_date task))
                                      (when (seq (:due_time task)) ","))])
        (when (seq (:due_time task))
          [:span.task-time {:class (when overdue? "overdue-time")} (:due_time task)])
        (:title task)
        (when is-expanded
          [:button.edit-icon {:on-click (fn [e]
                                          (.stopPropagation e)
                                          (state/set-editing-modal :task task))}
           "✎"])
        (when (and is-expanded (seq (:due_time task)))
          [task-item/time-picker task])]
       (when-not is-expanded
         [task-item/task-category-badges task])]
      (when-not hide-date
        [:span.task-date {:data-tooltip (date/get-day-name (:due_date task))}
         (date/format-date-localized (:due_date task))])]
     (when is-expanded
       [today-task-expanded-details task :show-unlink? show-unlink?])]))

(defn- show-success-notification! [message]
  (let [el (.createElement js/document "div")]
    (set! (.-className el) "success-notification")
    (set! (.-textContent el) message)
    (.appendChild (.-body js/document) el)
    (js/setTimeout #(.remove el) 2000)))

(defn- today-meet-create-next-button [meet]
  (when (and (:meeting_series_id meet)
             (:schedule_days meet)
             (not= (:schedule_days meet) ""))
    [:button.create-next-meeting-btn
     {:on-click (fn [e]
                  (.stopPropagation e)
                  (state/open-create-date-modal :meeting-series
                    (assoc meet :id (:meeting_series_id meet))))}
     (t :meets/create-meeting)]))

(defn- today-meet-archive-button [meet]
  (let [show? (or (nil? (:meeting_series_id meet))
                  (:series_has_future_meet meet))]
    (when show?
      [:button.archive-meet-btn
       {:on-click (fn [e]
                    (.stopPropagation e)
                    (state/archive-meet (:id meet)))}
       (t :meets/archive)])))

(defn- today-meet-expanded-details [meet]
  [:div.today-task-details
   (when (seq (:description meet))
     [:div.item-description [task-item/markdown (:description meet)]])
   (when (or (seq (:people meet)) (seq (:places meet)) (seq (:projects meet)))
     [:div.task-badges
      (for [person (:people meet)]
        ^{:key (str "person-" (:id person))}
        [:span.tag.person (filters/badge-label person)])
      (for [place (:places meet)]
        ^{:key (str "place-" (:id place))}
        [:span.tag.place (filters/badge-label place)])
      (for [project (:projects meet)]
        ^{:key (str "project-" (:id project))}
        [:span.tag.project (filters/badge-label project)])])
   [today-meet-create-next-button meet]
   [today-meet-archive-button meet]])

(defn- today-meet-item [meet & {:keys [show-day-prefix hide-date] :or {show-day-prefix false hide-date false}}]
  (let [show-prefix? (and show-day-prefix (date/within-days? (:start_date meet) 6))
        expanded-meet (:today-page/expanded-meet @state/*app-state)
        is-expanded (= expanded-meet (:id meet))]
    [:div.today-task-item.meet-item {:class (when is-expanded "expanded")}
     [:div.today-task-header
      {:on-click #(state/toggle-expanded :today-page/expanded-meet (:id meet))}
      [:div.today-task-content
       [:span.task-title
        "🗓️ "
        (when show-prefix?
          [:span.task-day-prefix (str (date/get-day-name (:start_date meet))
                                      (when (seq (:start_time meet)) ","))])
        (when (seq (:start_time meet))
          [:span.task-time (:start_time meet)])
        (:title meet)
        (when is-expanded
          [:button.edit-icon {:on-click (fn [e]
                                          (.stopPropagation e)
                                          (state/set-editing-modal :meet meet))}
           "✎"])]
       (when-not is-expanded
         (when (or (seq (:people meet)) (seq (:places meet)) (seq (:projects meet)))
           [:div.task-badges
            (for [person (:people meet)]
              ^{:key (str "person-" (:id person))}
              [:span.tag.person (filters/badge-label person)])
            (for [place (:places meet)]
              ^{:key (str "place-" (:id place))}
              [:span.tag.place (filters/badge-label place)])
            (for [project (:projects meet)]
              ^{:key (str "project-" (:id project))}
              [:span.tag.project (filters/badge-label project)])]))]
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

(defn- urgency-emoji [task]
  (case (:urgency task)
    "superurgent" "🚨🚨"
    "urgent" "🚨"
    nil))

(defn- today-overdue-section [overdue]
  (let [expanded-task (:today-page/expanded-task @state/*app-state)
        drag-enabled? (not expanded-task)]
    (when (seq overdue)
      [:div.today-section.overdue
       [:h3 (t :today/overdue)]
       [:div.task-list
        (doall
         (for [task overdue]
           ^{:key (:id task)}
           [:div.draggable-overdue-task
            {:draggable drag-enabled?
             :on-drag-start (drag-drop/make-drag-start-handler task state/set-drag-task drag-enabled?)
             :on-drag-end (fn [_] (state/clear-drag-state))}
            [today-task-item task :overdue? true :emoji-prefix (urgency-emoji task)]]))]])))


(defn- find-task-by-id* [task-id]
  (first (filter #(= (:id %) task-id) (:tasks @state/*app-state))))

(defn- find-task-by-id [task-id]
  (first (filter #(= (:id %) task-id) (:tasks @state/*app-state))))

(defn- ensure-urgency [task-id target-urgency]
  (let [task (find-task-by-id task-id)]
    (when (not= (:urgency task) target-urgency)
      (state/set-task-urgency task-id target-urgency))
    (when (= 1 (:today task))
      (state/set-task-today task-id false))
    (when (:lined_up_for task)
      (state/set-task-lined-up-for task-id nil))))

(defn- drag-task-overdue? []
  (let [drag-task-id (:drag-task @state/*app-state)
        today (date/today-str)]
    (when drag-task-id
      (let [task (find-task-by-id drag-task-id)]
        (and task (:due_date task) (< (:due_date task) today))))))

(defn- drag-task-urgent? []
  (when-let [drag-task-id (:drag-task @state/*app-state)]
    (let [task (find-task-by-id drag-task-id)]
      (and task (#{"urgent" "superurgent"} (:urgency task))))))

(defn- drag-task-other-things? []
  (when-let [drag-task-id (:drag-task @state/*app-state)]
    (let [task (find-task-by-id drag-task-id)]
      (and task (or (= 1 (:today task)) (:lined_up_for task))))))

(defn- add-task-for-selected-day [title on-success]
  (let [selected-day (or (:today-page/selected-day @state/*app-state) 0)]
    (if (zero? selected-day)
      (state/add-task-to-today title on-success)
      (state/add-task-lined-up-for title (state/selected-day-date) on-success))))

(defn- today-add-button []
  (let [ui-state (r/atom {:mode :closed})]
    (fn []
      (when-not (state/relation-mode-active?)
        (let [{:keys [mode input-value]} @ui-state]
          (case mode
            :closed
            [:button.today-add-btn
             {:on-click (fn [e]
                          (.stopPropagation e)
                          (swap! ui-state assoc :mode :input :input-value "")
                          (js/setTimeout #(when-let [el (.querySelector js/document ".today-add-input")]
                                            (.focus el)) 0))}
             "+"]

            :input
            [:div.today-add-form
             [:input.today-add-input
              {:type "text"
               :placeholder (t :tasks/add-placeholder)
               :value (or input-value "")
               :on-change #(swap! ui-state assoc :input-value (.. % -target -value))
               :on-key-down (fn [e]
                              (when (= "Enter" (.-key e))
                                (let [title (.-value (.-target e))]
                                  (when (seq (.trim title))
                                    (add-task-for-selected-day title #(swap! ui-state assoc :mode :closed))
                                    (swap! ui-state assoc :mode :closed))))
                              (when (= "Escape" (.-key e))
                                (swap! ui-state assoc :mode :closed)))}]
             [:button.today-add-submit
              {:on-click (fn [e]
                           (.stopPropagation e)
                           (let [title (:input-value @ui-state)]
                             (when (seq (.trim (or title "")))
                               (add-task-for-selected-day title #(swap! ui-state assoc :mode :closed))
                               (swap! ui-state assoc :mode :closed))))}
              (t :tasks/add-button)]]

            nil))))))

(defn- handle-other-things-drop [drag-task-id]
  (let [selected-day (or (:today-page/selected-day @state/*app-state) 0)]
    (if (zero? selected-day)
      (state/set-task-today drag-task-id true)
      (state/set-task-lined-up-for drag-task-id (state/selected-day-date)))
    (state/clear-drag-state)))

(defn- handle-day-section-drop [drag-task-id target-date]
  (let [task (find-task-by-id* drag-task-id)]
    (swap! state/*app-state assoc :today-page/confirm-move-to-today
           {:task task :target-date target-date})
    (state/clear-drag-state)))

(defn- ensure-other-things [task-id]
  (let [task (find-task-by-id* task-id)
        selected-day (or (:today-page/selected-day @state/*app-state) 0)]
    (if (zero? selected-day)
      (do (when (not= 1 (:today task))
            (state/set-task-today task-id true))
          true)
      (let [target-date (state/selected-day-date)]
        (when (not= (:lined_up_for task) target-date)
          (state/set-task-lined-up-for task-id target-date))
        true))))

(defn- handle-today-flagged-drop [e drag-task-id target-task]
  (.preventDefault e)
  (.stopPropagation e)
  (when (and drag-task-id (not= drag-task-id (:id target-task)))
    (let [rect (.getBoundingClientRect (.-currentTarget e))
          y (.-clientY e)
          mid-y (+ (.-top rect) (/ (.-height rect) 2))
          position (if (< y mid-y) "before" "after")]
      (when (ensure-other-things drag-task-id)
        (state/reorder-task drag-task-id (:id target-task) position))))
  (state/clear-drag-state))

(defn- draggable-today-flagged-task-item [task drag-task-id drag-enabled?]
  (let [drag-over-task (:drag-over-task @state/*app-state)
        is-dragging (= drag-task-id (:id task))
        accept-drop? (and drag-enabled? (not (drag-task-overdue?)))
        is-drag-over (and accept-drop? (= drag-over-task (:id task)))]
    [:div.draggable-today-task
     {:class (str (when is-dragging "dragging")
                  (when is-drag-over " drag-over"))
      :draggable drag-enabled?
      :on-drag-start (drag-drop/make-drag-start-handler task state/set-drag-task drag-enabled?)
      :on-drag-end (fn [_] (state/clear-drag-state))
      :on-drag-over (drag-drop/make-drag-over-handler task state/set-drag-over-task accept-drop?)
      :on-drag-leave (drag-drop/make-drag-leave-handler drag-over-task task #(state/set-drag-over-task nil))
      :on-drop (fn [e] (when accept-drop? (handle-today-flagged-drop e drag-task-id task)))}
     [today-task-item task :hide-date true :emoji-prefix (urgency-emoji task) :show-unlink? true]]))

(defn- handle-day-button-drop [drag-task-id target-date]
  (let [task (find-task-by-id* drag-task-id)
        today (date/today-str)]
    (cond
      (and task (:due_date task) (< (:due_date task) today))
      (handle-day-section-drop drag-task-id target-date)

      (or (= 1 (:today task)) (:lined_up_for task))
      (do (if (= target-date today)
            (state/set-task-today drag-task-id true)
            (state/set-task-lined-up-for drag-task-id target-date))
          (state/clear-drag-state)))))

(defn- day-selector []
  (let [selected-day (or (:today-page/selected-day @state/*app-state) 0)
        today (date/today-str)
        drag-task (:drag-task @state/*app-state)
        expanded-task (:today-page/expanded-task @state/*app-state)
        drag-enabled? (and drag-task (not expanded-task))
        from-overdue? (drag-task-overdue?)
        from-other-things? (drag-task-other-things?)
        drop-enabled? (and drag-enabled? (or from-overdue? from-other-things?))]
    [:div.day-selector.toggle-group {:class (when drop-enabled? "dragging")}
     (doall
      (for [offset (range 5)]
        (let [target-date (date/add-days today offset)
              label (if (zero? offset)
                      (t :today/today)
                      (date/get-day-name target-date))
              is-source-day? (and from-other-things? (= offset selected-day))
              btn-drop? (and drop-enabled? (not is-source-day?))]
          ^{:key offset}
          [:button {:class (str (when (= selected-day offset) "active")
                                (when (and btn-drop? (= (:drag-over-urgency-section @state/*app-state) (keyword (str "day-" offset)))) " drop-target"))
                    :on-click #(state/set-selected-day offset)
                    :on-drag-over (fn [e]
                                    (when btn-drop?
                                      (.preventDefault e)
                                      (state/set-drag-over-urgency-section (keyword (str "day-" offset)))))
                    :on-drag-leave (fn [e]
                                     (when (= (.-target e) (.-currentTarget e))
                                       (state/set-drag-over-urgency-section nil)))
                    :on-drop (fn [e]
                               (when btn-drop?
                                 (.preventDefault e)
                                 (state/set-drag-over-urgency-section nil)
                                 (handle-day-button-drop drag-task target-date)))}
           label])))]))

(defn- today-today-section [day-tasks day-meets today-flagged]
  (let [selected-day (or (:today-page/selected-day @state/*app-state) 0)
        is-today? (zero? selected-day)
        target-date (state/selected-day-date)
        items (interleave-by-date day-tasks day-meets)
        drag-task (:drag-task @state/*app-state)
        expanded-task (:today-page/expanded-task @state/*app-state)
        drag-enabled? (and drag-task (not expanded-task))
        from-overdue? (drag-task-overdue?)
        from-urgent? (drag-task-urgent?)
        from-other-things? (drag-task-other-things?)
        due-drop-enabled? (and drag-enabled? (not from-urgent?) (not from-other-things?))]
    [:div.today-section.today
     [:div.today-section-header
      [:h3 (date/day-formatted target-date)]]
     [:div.today-subsection
      {:class (when (and due-drop-enabled? (= (:drag-over-urgency-section @state/*app-state) :due-or-happening)) "drop-target")
       :on-drag-over (fn [e]
                       (when due-drop-enabled?
                         (.preventDefault e)
                         (state/set-drag-over-urgency-section :due-or-happening)))
       :on-drag-leave (fn [e]
                        (when (= (.-target e) (.-currentTarget e))
                          (state/set-drag-over-urgency-section nil)))
       :on-drop (fn [e]
                  (when due-drop-enabled?
                    (.preventDefault e)
                    (handle-day-section-drop drag-task target-date)))}
      [:h4 (if is-today?
             (t :today/due-or-happening)
             (t :today/due-or-happening-on {:day (date/get-day-name target-date)}))]
      (if (seq items)
        [:div.task-list
         (doall
          (for [item items]
            (if (= (:item-type item) :meet)
              ^{:key (str "meet-" (:id item))}
              [today-meet-item item :hide-date true]
              ^{:key (str "task-" (:id item))}
              [today-task-item item :hide-date true :emoji-prefix (if (seq (:due_time item)) "⏰" "⏳")])))]
        [:p.empty-urgency-message (t :today/no-tasks-in-section)])]
     (let [other-drop-enabled? (and drag-enabled? (not from-overdue?))]
       [:div.today-subsection.other-things
        {:class (when (and other-drop-enabled? (= (:drag-over-urgency-section @state/*app-state) :other-things)) "drop-target")
         :on-drag-over (fn [e]
                         (when other-drop-enabled?
                           (.preventDefault e)
                           (state/set-drag-over-urgency-section :other-things)))
         :on-drag-leave (fn [e]
                          (when (= (.-target e) (.-currentTarget e))
                            (state/set-drag-over-urgency-section nil)))
         :on-drop (fn [e]
                    (when other-drop-enabled?
                      (.preventDefault e)
                      (handle-other-things-drop drag-task)))}
      [:div.today-subsection-header
       [:h4 (t :today/other-things)]
       [today-add-button]]
      (if (seq today-flagged)
        (let [flagged-drag-enabled? (not expanded-task)
              drag-task-id (:drag-task @state/*app-state)]
          [:div.task-list.today-flagged
           (doall
            (for [task today-flagged]
              ^{:key (str "flagged-" (:id task))}
              [draggable-today-flagged-task-item task drag-task-id flagged-drag-enabled?]))])
        [:p.empty-urgency-message (t :today/no-tasks-in-section)])])]))

(defn- draggable-urgent-task-item [task target-urgency drag-enabled?]
  (let [drag-task (:drag-task @state/*app-state)
        drag-over-task (:drag-over-task @state/*app-state)
        is-dragging (= drag-task (:id task))
        is-drag-over (= drag-over-task (:id task))
        accept-drop? (and drag-enabled? (not (drag-task-overdue?)))]
    [:div.draggable-urgent-task
     {:class (str (when is-dragging "dragging")
                  (when is-drag-over " drag-over")
                  (when-not drag-enabled? " drag-disabled"))
      :draggable drag-enabled?
      :on-drag-start (drag-drop/make-drag-start-handler task state/set-drag-task drag-enabled?)
      :on-drag-end (fn [_] (state/clear-drag-state))
      :on-drag-over (drag-drop/make-drag-over-handler task state/set-drag-over-task accept-drop?)
      :on-drag-leave (drag-drop/make-drag-leave-handler drag-over-task task #(state/set-drag-over-task nil))
      :on-drop (drag-drop/make-urgency-task-drop-handler drag-task task target-urgency ensure-urgency state/reorder-task accept-drop?)}
     [today-task-item task]]))

(defn- urgency-task-list [tasks target-urgency drag-enabled?]
  (let [drag-task (:drag-task @state/*app-state)
        drag-over-section (:drag-over-urgency-section @state/*app-state)
        is-section-drag-over (= drag-over-section target-urgency)
        accept-drop? (and drag-enabled? (not (drag-task-overdue?)))]
    [:div.urgency-task-list
     {:class (str (when is-section-drag-over "section-drag-over")
                  (when-not drag-enabled? " drag-disabled"))
      :on-drag-over (fn [e]
                      (when accept-drop?
                        (.preventDefault e)
                        (state/set-drag-over-urgency-section target-urgency)))
      :on-drag-leave (fn [e]
                       (when (= (.-target e) (.-currentTarget e))
                         (state/set-drag-over-urgency-section nil)))
      :on-drop (drag-drop/make-urgency-section-drop-handler drag-task tasks target-urgency ensure-urgency state/reorder-task state/clear-drag-state accept-drop?)}
     (if (seq tasks)
       (doall
        (for [task tasks]
          ^{:key (:id task)}
          [draggable-urgent-task-item task target-urgency drag-enabled?]))
       [:p.empty-urgency-message (t :today/no-tasks-in-section)])]))

(defn- today-urgent-section [superurgent urgent]
  (let [expanded-task (:today-page/expanded-task @state/*app-state)
        drag-enabled? (not expanded-task)]
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

(defn- confirm-move-to-today-modal []
  (when-let [{:keys [task target-date]} (:today-page/confirm-move-to-today @state/*app-state)]
    (let [today (date/today-str)
          is-today? (= target-date today)]
      [:div.modal-overlay
       [:div.modal {:on-click #(.stopPropagation %)}
        [:div.modal-header (if is-today?
                             (t :modal/move-to-today)
                             (t :modal/adjust-due-date))]
        [:div.modal-body
         [:p (if is-today?
               (t :modal/move-to-today-confirm)
               (t :modal/adjust-due-date-confirm {:date (date/day-formatted target-date)}))]
         [:p.task-title (:title task)]]
        [:div.modal-footer
         [:button.cancel
          {:on-click #(swap! state/*app-state assoc :today-page/confirm-move-to-today nil)}
          (t :modal/cancel)]
         [:button.confirm-delete
          {:on-click (fn []
                       (state/set-task-due-date (:id task) target-date)
                       (swap! state/*app-state assoc :today-page/confirm-move-to-today nil))}
          (t :modal/confirm)]]]])))

(defn today-tab []
  (let [overdue (state/overdue-tasks)
        day-tasks (state/selected-day-tasks)
        day-meets (state/selected-day-meets)
        today-flagged (state/today-flagged-tasks)
        superurgent (state/superurgent-tasks)
        urgent (state/urgent-tasks)
        upcoming (state/upcoming-tasks)
        upcoming-m (state/upcoming-meets)
        selected-view (:today-page/selected-view @state/*app-state)]
    [:div
     [confirm-move-to-today-modal]
     [:div.main-layout
      [today-sidebar-filters]
      [:div.main-content.today-content
       [today-overdue-section overdue]
       [day-selector]
       [today-today-section day-tasks day-meets today-flagged]
       [today-view-switcher]
       (when (= selected-view :urgent)
         [today-urgent-section superurgent urgent])
       (when (= selected-view :upcoming)
         [today-upcoming-section upcoming upcoming-m])]]]))
