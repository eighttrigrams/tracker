(ns et.tr.ui.views.today
  (:require [clojure.string]
            [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.date :as date]
            [et.tr.ui.modals :as modals]
            [et.tr.ui.components.drag-drop :as drag-drop]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.ui.components.item-card :as item-card]
            [et.tr.ui.components.filter-section :as filter-section]
            [et.tr.i18n :refer [t]]))

(def ^:private today-category-shortcut-keys
  {"Digit1" :people
   "Digit2" :places
   "Digit3" :projects
   "Digit4" :goals})

(def today-category-shortcut-numbers
  (into {} (map (fn [[k v]] [v (subs k 5)]) today-category-shortcut-keys)))

(defn get-today-category-shortcut-keys []
  today-category-shortcut-keys)

(defn today-task-item [task & {:keys [show-day-prefix overdue? hide-date emoji-prefix show-unlink?] :or {show-day-prefix false overdue? false hide-date false}}]
  (let [show-prefix? (and show-day-prefix (date/within-days? (:due_date task) 6))
        is-expanded (= (:today-page/expanded-task @state/*app-state) (:id task))
        maybe? (= 1 (:maybe task))]
    [item-card/item-card
     {:item task
      :expanded? is-expanded
      :on-toggle #(state/toggle-expanded :today-page/expanded-task (:id task))
      :container {:tag :div
                  :class (str "today-task-item" (when maybe? " maybe"))
                  :classes {:header "today-task-header"
                            :title "today-task-content"
                            :content "today-task-details"}}
      :relation-link [:task (:id task)]
      :inline-edit (item-card/make-inline-edit
                     {:edit-id-path :today-page/inline-edit-task
                      :title-path :today-page/inline-edit-title
                      :update-fn state/update-task})
      :title-content (fn [{:keys [item expanded? title-el]}]
                       [:<>
                        [:span.task-title
                         (when emoji-prefix
                           [:span.task-emoji-prefix emoji-prefix])
                         (when show-prefix?
                           [:span.task-day-prefix (str (date/get-day-name (:due_date item))
                                                       (when (seq (:due_time item)) ","))])
                         (when (seq (:due_time item))
                           [:span.task-time {:class (when overdue? "overdue-time")} (:due_time item)])
                         title-el]
                        (when-not expanded?
                          [task-item/task-category-badges item])])
      :toolbar {:calendar {:on-click #(state/set-editing-modal :task task :time)}}
      :header-extra [:<>
                     (when-not hide-date
                       [:span.task-date {:data-tooltip (date/get-day-name (:due_date task))}
                        (date/format-date-localized (:due_date task))])
                     (when (:recurring_task_id task)
                       [:span.recurrence-icon {:on-click (fn [e]
                                                           (.stopPropagation e)
                                                           (swap! state/*app-state assoc
                                                                  :tasks-page/filter-recurring {:id (:recurring_task_id task) :title (:title task)}
                                                                  :tasks-page/recurring-mode false)
                                                           (state/set-active-tab :tasks))}
                        "🔁"])
                     (when (and (not is-expanded) (or (:reminder_date task) (= "active" (:reminder task))))
                       [:span.reminder-icon "🔔"])]
      :description {:edit-type :task}
      :categories {:selector-fn task-item/category-selector
                   :relations-prefix "tsk"
                   :readonly-fn (fn [_] nil)}
      :footer {:left (into [{:type :scope :value (:scope task)
                             :on-set #(state/set-task-scope (:id task) %)}
                            {:type :importance :value (:importance task)
                             :on-set #(state/set-task-importance (:id task) %)}]
                           (when-not (:due_date task)
                             [{:type :urgency :value (:urgency task)
                               :on-set #(state/set-task-urgency (:id task) %)}]))
               :right [{:type :done
                        :item task
                        :extra-dropdown-items
                        (when show-unlink?
                          [{:label (if maybe? (t :task/unset-maybe) (t :task/set-maybe))
                            :class "toggle-maybe"
                            :on-click #(do
                                         (state/set-task-dropdown-open nil)
                                         (state/set-task-maybe (:id task) (not maybe?)))}
                           {:label (t :task/unlink-today)
                            :class "unlink-today"
                            :on-click #(let [selected-day (or (:today-page/selected-day @state/*app-state) 0)]
                                         (state/set-task-dropdown-open nil)
                                         (if (zero? selected-day)
                                           (state/set-task-today (:id task) false)
                                           (state/set-task-lined-up-for (:id task) nil)))}])}]}}]))

(defn- meet-archivable? [meet is-today]
  (let [future? (and (:start_date meet)
                     (pos? (compare (:start_date meet) (date/today-str))))]
    (and is-today
         (not future?)
         (or (nil? (:meeting_series_id meet))
             (:series_has_future_meet meet)))))

(defn- meet-footer-spec [meet is-today gray-when-maybe]
  (let [maybe? (= 1 (:maybe meet))
        close! #(state/set-meet-dropdown-open nil)
        actions (cond-> []
                  (meet-archivable? meet is-today)
                  (conj {:variant :done
                         :label (t :meets/archive)
                         :on-click #(do (close!) (state/archive-meet (:id meet)))})
                  gray-when-maybe
                  (conj {:variant :done
                         :class "toggle-maybe"
                         :label (if maybe? (t :task/unset-maybe) (t :task/set-maybe))
                         :on-click #(do (close!) (state/set-meet-maybe (:id meet) (not maybe?)))})
                  true
                  (conj {:variant :delete
                         :label (t :task/delete)
                         :on-click #(do (close!) (state/set-confirm-delete-meet meet))}))
        anchor (first actions)
        items (rest actions)]
    (cond-> {:type :button
             :variant (:variant anchor)
             :label (:label anchor)
             :on-click (:on-click anchor)}
      (:class anchor) (assoc :class (:class anchor))
      (seq items) (assoc :dropdown
                         {:open? (= (:id meet) (:meet-dropdown-open @state/*app-state))
                          :on-toggle #(state/set-meet-dropdown-open (:id meet))
                          :items (mapv (fn [a]
                                         {:label (:label a)
                                          :class (:class a)
                                          :on-click (:on-click a)})
                                       items)}))))

(defn- today-meet-item [meet & {:keys [show-day-prefix hide-date is-today gray-when-maybe] :or {show-day-prefix false hide-date false is-today false gray-when-maybe false}}]
  (let [show-prefix? (and show-day-prefix (date/within-days? (:start_date meet) 6))
        is-expanded (= (:today-page/expanded-meet @state/*app-state) (:id meet))
        maybe? (= 1 (:maybe meet))]
    [item-card/item-card
     {:item meet
      :expanded? is-expanded
      :on-toggle #(state/toggle-expanded :today-page/expanded-meet (:id meet))
      :container {:tag :div
                  :class (str "today-task-item meet-item" (when (and gray-when-maybe maybe?) " maybe"))
                  :classes {:header "today-task-header"
                            :title "today-task-content"
                            :content "today-task-details"}}
      :relation-link [:meet (:id meet)]
      :inline-edit (item-card/make-inline-edit
                     {:edit-id-path :today-page/inline-edit-meet
                      :title-path :today-page/inline-edit-meet-title
                      :update-fn state/update-meet})
      :title-content (fn [{:keys [item expanded? title-el]}]
                       [:<>
                        [:span.task-title
                         "🗓️ "
                         (when show-prefix?
                           [:span.task-day-prefix (str (date/get-day-name (:start_date item))
                                                       (when (seq (:start_time item)) ","))])
                         (when (seq (:start_time item))
                           [:span.task-time (:start_time item)])
                         title-el]
                        (when-not expanded?
                          [task-item/task-category-badges item])])
      :toolbar {:calendar {:on-click #(state/set-editing-modal :meet meet :time)}}
      :header-extra [:<>
                     (when-not hide-date
                       [:span.task-date {:data-tooltip (date/get-day-name (:start_date meet))}
                        (date/format-date-localized (:start_date meet))])
                     (when (:meeting_series_id meet)
                       [:span.recurrence-icon {:on-click (fn [e]
                                                           (.stopPropagation e)
                                                           (swap! state/*app-state assoc
                                                                  :meets-page/filter-series {:id (:meeting_series_id meet) :title (:title meet)}
                                                                  :meets-page/series-mode false)
                                                           (state/set-active-tab :meets))}
                        "🔁"])]
      :description {:edit-type :meet}
      :categories {:selector-fn task-item/meet-category-selector
                   :relations-prefix "met"
                   :readonly-fn (fn [_] nil)}
      :footer {:left [{:type :scope :value (:scope meet)
                       :on-set #(state/set-meet-scope (:id meet) %)}
                      {:type :importance :value (:importance meet)
                       :on-set #(state/set-meet-importance (:id meet) %)}]
               :right [(meet-footer-spec meet is-today gray-when-maybe)]}}]))

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


(defn- today-filter-section [{:keys [title filter-key items selected-ids toggle-fn clear-fn collapsed?]}]
  [filter-section/category-filter-section {:title title
                                           :shortcut-number (today-category-shortcut-numbers filter-key)
                                           :filter-key filter-key
                                           :items items
                                           :marked-ids selected-ids
                                           :toggle-fn toggle-fn
                                           :clear-fn clear-fn
                                           :collapsed? collapsed?
                                           :toggle-collapsed-fn state/toggle-today-filter-collapsed
                                           :set-search-fn state/set-today-category-search
                                           :search-state-path [:today-page/category-search filter-key]
                                           :section-class (name filter-key)
                                           :item-active-class "active"
                                           :label-class nil
                                           :page-prefix "today"}])

(def ^:private today-sidebar-filter-configs
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
    :filter-state-key :shared/filter-goals
    :category-type state/CATEGORY-TYPE-GOAL}])

(defn today-sidebar-filters []
  (let [app-state @state/*app-state
        collapsed-filters (:today-page/collapsed-filters app-state)]
    (into [:div.sidebar [filter-section/category-badge-toggle]]
          (for [{:keys [filter-key title-key items-key filter-state-key category-type]} today-sidebar-filter-configs]
            [today-filter-section {:title (t title-key)
                                   :filter-key filter-key
                                   :items (get app-state items-key)
                                   :selected-ids (get app-state filter-state-key)
                                   :toggle-fn #(state/toggle-shared-filter category-type %)
                                   :clear-fn #(state/clear-shared-filter category-type)
                                   :collapsed? (contains? collapsed-filters filter-key)}]))))

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

(defn- drag-task-reminder? []
  (= :reminder (:drag-task-source @state/*app-state)))

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
               :auto-complete "off"
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
  (let [selected-day (or (:today-page/selected-day @state/*app-state) 0)
        from-reminder? (drag-task-reminder?)]
    (if (zero? selected-day)
      (state/set-task-today drag-task-id true)
      (state/set-task-lined-up-for drag-task-id (state/selected-day-date)))
    (when from-reminder?
      (state/acknowledge-task-reminder drag-task-id))
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
  (let [from-reminder? (drag-task-reminder?)]
    (when (and drag-task-id (not= drag-task-id (:id target-task)))
      (let [rect (.getBoundingClientRect (.-currentTarget e))
            y (.-clientY e)
            mid-y (+ (.-top rect) (/ (.-height rect) 2))
            position (if (< y mid-y) "before" "after")]
        (when (ensure-other-things drag-task-id)
          (state/reorder-task drag-task-id (:id target-task) position))))
    (when (and drag-task-id from-reminder?)
      (state/acknowledge-task-reminder drag-task-id)))
  (state/clear-drag-state))

(defn- draggable-today-flagged-task-item [task drag-task-id drag-enabled?]
  (let [drag-over-task (:drag-over-task @state/*app-state)
        is-dragging (= drag-task-id (:id task))
        accept-drop? (and drag-enabled? (or (drag-task-reminder?) (not (drag-task-overdue?))))
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
        today (date/today-str)
        from-reminder? (drag-task-reminder?)]
    (cond
      from-reminder?
      (do (if (= target-date today)
            (state/set-task-today drag-task-id true)
            (state/set-task-lined-up-for drag-task-id target-date))
          (state/acknowledge-task-reminder drag-task-id)
          (state/clear-drag-state))

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
        from-reminder? (drag-task-reminder?)
        drop-enabled? (and drag-enabled? (or from-overdue? from-other-things? from-reminder?))]
    [:div.day-selector.toggle-group {:class (when drop-enabled? "dragging")}
     (doall
      (for [offset (range 5)]
        (let [target-date (date/add-days today offset)
              label (if (zero? offset)
                      (t :today/today)
                      (date/get-day-label target-date))
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
        from-reminder? (drag-task-reminder?)
        due-drop-enabled? (and drag-enabled? (not from-urgent?) (not from-other-things?) (not from-reminder?))]
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
              [today-meet-item item :hide-date true :is-today is-today? :gray-when-maybe true]
              ^{:key (str "task-" (:id item))}
              [today-task-item item :hide-date true :emoji-prefix (if (seq (:due_time item)) "⏰" "⏳")])))]
        [:p.empty-urgency-message (t :today/no-tasks-in-section)])]
     (let [other-drop-enabled? (and drag-enabled? (or from-reminder? (not from-overdue?)))]
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
        accept-drop? (and drag-enabled? (not (drag-task-overdue?)) (not (drag-task-reminder?)))]
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
        accept-drop? (and drag-enabled? (not (drag-task-overdue?)) (not (drag-task-reminder?)))]
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

(defn- draggable-reminder-task-item [task drag-enabled?]
  (let [drag-task-id (:drag-task @state/*app-state)
        is-dragging (= drag-task-id (:id task))]
    [:div.draggable-reminder-task
     {:class (str (when is-dragging "dragging")
                  (when-not drag-enabled? " drag-disabled"))
      :draggable drag-enabled?
      :on-drag-start (fn [e]
                       (when drag-enabled?
                         (.setData (.-dataTransfer e) "text/plain" (str (:id task)))
                         (state/set-drag-task (:id task))
                         (swap! state/*app-state assoc :drag-task-source :reminder)))
      :on-drag-end (fn [_] (state/clear-drag-state))}
     [today-task-item task]]))

(defn- today-reminders-section [reminder-tasks]
  (let [expanded-task (:today-page/expanded-task @state/*app-state)
        drag-enabled? (not expanded-task)]
    [:div.today-section.reminders
     [:h3.reminders-heading (t :today/reminders)
      (when (seq reminder-tasks)
        [:span.reminder-indicator])]
     (if (seq reminder-tasks)
       [:div.task-list
        (doall
         (for [task reminder-tasks]
           ^{:key (str "task-" (:id task))}
           [draggable-reminder-task-item task drag-enabled?]))]
       [:p.empty-message (t :today/no-reminders)])]))

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
  (let [selected-view (:today-page/selected-view @state/*app-state)
        reminder-count (count (state/reminder-tasks))]
    [:div.today-view-switcher.toggle-group
     [:button {:class (when (= selected-view :urgent) "active")
               :on-click #(state/set-today-selected-view :urgent)}
      (t :today/urgent-matters)]
     [:button {:class (when (= selected-view :upcoming) "active")
               :on-click #(state/set-today-selected-view :upcoming)}
      (t :today/upcoming)]
     [:button {:class (str (when (= selected-view :reminders) "active")
                           (when (and (not= selected-view :reminders) (pos? reminder-count)) " has-reminders"))
               :on-click #(state/set-today-selected-view :reminders)}
      (t :today/reminders)
      (when (and (not= selected-view :reminders) (pos? reminder-count))
        [:span.reminder-indicator])]]))

(defn- confirm-move-to-today-modal []
  (when-let [{:keys [task target-date]} (:today-page/confirm-move-to-today @state/*app-state)]
    (let [today (date/today-str)
          is-today? (= target-date today)
          cancel #(swap! state/*app-state assoc :today-page/confirm-move-to-today nil)
          confirm (fn []
                    (state/set-task-due-date (:id task) target-date)
                    (swap! state/*app-state assoc :today-page/confirm-move-to-today nil))]
      [:div.modal-overlay
       [modals/modal-keyboard-shortcut {:on-confirm confirm :on-escape cancel :enabled? true}]
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
         [:button.cancel {:on-click cancel} (t :modal/cancel)]
         [:button.confirm-delete {:on-click confirm} (t :modal/confirm)]]]])))

(defn- today-journals-toggle []
  (let [journals-mode (state/today-journals-mode?)]
    [:div.series-mode-toggle.toggle-group
     [:button {:class (when journals-mode "active")
               :on-click #(state/toggle-today-journals-mode)}
      (t :today/journals)]]))

(defn- today-journals-summary-toggle []
  (let [summary-mode? (:today-page/journal-summary-mode @state/*app-state)]
    [:button.journal-summary-btn
     {:class (when summary-mode? "active")
      :on-click #(swap! state/*app-state update :today-page/journal-summary-mode not)}
     "📋"]))

(defn- today-journal-entries-summary [entries]
  [:div.journal-entries-summary
   (for [entry entries]
     ^{:key (:id entry)}
     [:div.journal-entry-summary-item
      [:div.journal-entry-summary-header
       [:span.journal-entry-summary-title (:title entry)]
       (when (:entry_date entry)
         [:span.journal-entry-summary-date (date/format-date-localized (:entry_date entry))])]
      (if (seq (:description entry))
        [:div.journal-entry-summary-description
         {:on-click #(state/set-editing-modal :journal-entry entry)}
         [task-item/markdown (:description entry)]]
        [:button.edit-icon.description-placeholder
         {:on-click (fn [e]
                      (.stopPropagation e)
                      (state/set-editing-modal :journal-entry entry))}
         "✎"])])])

(defn- today-journal-entry-item [entry]
  (let [is-expanded (= (:today-page/expanded-journal-entry @state/*app-state) (:id entry))]
    [item-card/item-card
     {:item entry
      :expanded? is-expanded
      :on-toggle #(swap! state/*app-state assoc :today-page/expanded-journal-entry
                         (when-not is-expanded (:id entry)))
      :container {:tag :li
                  :classes {:content "today-task-details"}}
      :relation-link [:journal-entry (:id entry)]
      :inline-edit (item-card/make-inline-edit
                     {:edit-id-path :today-page/inline-edit-journal-entry
                      :title-path :today-page/inline-edit-journal-entry-title
                      :update-fn state/update-journal-entry})
      :date {:render (fn [e]
                       [:<>
                        (when (:entry_date e)
                          [:span.due-date (date/format-date-localized (:entry_date e))])
                        (when (:journal_id e)
                          [:span.recurrence-icon {:on-click (fn [ev]
                                                              (.stopPropagation ev)
                                                              (state/set-journal-filter {:id (:journal_id e) :title (:title e)})
                                                              (state/set-active-tab :resources))}
                           "🔁"])])}
      :description {:edit-type :journal-entry}
      :categories {:selector-fn task-item/journal-entry-category-selector
                   :relations-prefix "jen"}}]))

(defn- today-journals-section []
  (let [entries (:today-journal-entries @state/*app-state)]
    [:div.today-section
     (if (empty? entries)
       [:p.empty-message (t :journals/no-entries)]
       (if (:today-page/journal-summary-mode @state/*app-state)
         [today-journal-entries-summary entries]
         [:ul.items
          (for [entry entries]
            ^{:key (:id entry)}
            [today-journal-entry-item entry])]))]))

(defn today-tab []
  (let [overdue (state/overdue-tasks)
        day-tasks (state/selected-day-tasks)
        day-meets (state/selected-day-meets)
        today-flagged (state/today-flagged-tasks)
        superurgent (state/superurgent-tasks)
        urgent (state/urgent-tasks)
        upcoming (state/upcoming-tasks)
        upcoming-m (state/upcoming-meets)
        reminders (state/reminder-tasks)
        selected-view (:today-page/selected-view @state/*app-state)
        journals-mode (state/today-journals-mode?)]
    [:div
     [confirm-move-to-today-modal]
     [:div.main-layout
      [today-sidebar-filters]
      [:div.main-content.today-content
       [today-overdue-section overdue]
       [:div.tasks-header
        [today-journals-toggle]
        (when journals-mode
          [today-journals-summary-toggle])
        (when-not journals-mode
          [day-selector])]
       (if journals-mode
         [today-journals-section]
         [:<>
          [today-today-section day-tasks day-meets today-flagged]
          [today-view-switcher]
          (when (= selected-view :urgent)
            [today-urgent-section superurgent urgent])
          (when (= selected-view :upcoming)
            [today-upcoming-section upcoming upcoming-m])
          (when (= selected-view :reminders)
            [today-reminders-section reminders])])]]]))
