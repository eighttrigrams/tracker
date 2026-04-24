(ns et.tr.ui.views.meets
  (:require [et.tr.ui.state :as state]
            [et.tr.ui.state.meets :as meets-state]
            [et.tr.ui.state.meeting-series :as meeting-series-state]
            [et.tr.ui.date :as date]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.ui.components.filter-section :as filter-section]
            [et.tr.ui.components.category-selector :as category-selector]
            [et.tr.ui.components.relation-link :as relation-link]
            [et.tr.ui.components.relation-badges :as relation-badges]
            [et.tr.i18n :refer [t]]))

(declare series-create-meeting-button)

(def ^:private meets-category-shortcut-keys
  {"Digit1" :people
   "Digit2" :places
   "Digit3" :projects
   "Digit4" :goals})

(def meets-category-shortcut-numbers
  (into {} (map (fn [[k v]] [v (subs k 5)]) meets-category-shortcut-keys)))

(defn get-meets-category-shortcut-keys []
  meets-category-shortcut-keys)

(defn- meet-scope-selector [meet]
  (let [scope (or (:scope meet) "both")]
    [:div.task-scope-selector.toggle-group.compact
     (for [s ["private" "both" "work"]]
       ^{:key s}
       [:button.toggle-option
        {:class (when (= scope s) "active")
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-meet-scope (:id meet) s))}
        s])]))

(defn- meet-importance-selector [meet]
  (let [importance (or (:importance meet) "normal")]
    [:div.task-importance-selector.toggle-group.compact
     (for [[level label] [["normal" "○"] ["important" "★"] ["critical" "★★"]]]
       ^{:key level}
       [:button.toggle-option
        {:class (str level (when (= importance level) " active"))
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-meet-importance (:id meet) level))}
        label])]))

(defn- meet-category-selector [meet category-type entities label]
  (let [current-categories (case category-type
                             state/CATEGORY-TYPE-PERSON (:people meet)
                             state/CATEGORY-TYPE-PLACE (:places meet)
                             state/CATEGORY-TYPE-PROJECT (:projects meet)
                             state/CATEGORY-TYPE-GOAL (:goals meet)
                             [])]
    [category-selector/category-selector
     {:entity meet
      :entity-id-key :id
      :category-type category-type
      :entities entities
      :label label
      :current-categories current-categories
      :on-categorize #(state/categorize-meet (:id meet) category-type %)
      :on-uncategorize #(state/uncategorize-meet (:id meet) category-type %)
      :on-close-focus-fn nil
      :open-selector-state (:category-selector/open @state/*app-state)
      :search-state (:category-selector/search @state/*app-state)
      :open-selector-fn state/open-category-selector
      :close-selector-fn state/close-category-selector
      :set-search-fn state/set-category-selector-search}]))

(defn- meet-date-time-pickers [meet]
  [:button.calendar-icon {:on-click (fn [e]
                                      (.stopPropagation e)
                                      (state/set-editing-modal :meet meet :time))}
   "📅"])

(defn- meet-expanded-view [meet people places projects goals]
  [:div.item-details
   (if (seq (:description meet))
     [task-item/clampable-description
      {:text (:description meet)
       :on-click #(state/set-editing-modal :meet meet)}]
     [:button.edit-icon.description-placeholder
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (state/set-editing-modal :meet meet))}
      "✎"])
   [:div.item-tags
    [meet-category-selector meet state/CATEGORY-TYPE-PERSON people (t :category/person)]
    [meet-category-selector meet state/CATEGORY-TYPE-PLACE places (t :category/place)]
    [meet-category-selector meet state/CATEGORY-TYPE-PROJECT projects (t :category/project)]
    [meet-category-selector meet state/CATEGORY-TYPE-GOAL goals (t :category/goal)]
    [relation-badges/relation-badges-expanded (:relations meet) "met" (:id meet)]]
   [:div.item-actions
    [meet-scope-selector meet]
    [meet-importance-selector meet]
    [:div.combined-button-wrapper
     [:button.delete-btn {:on-click #(state/set-confirm-delete-meet meet)}
      (t :task/delete)]]]])

(defn- meet-inline-title-edit [meet]
  (let [value (or (:meets-page/inline-edit-title @state/*app-state) "")]
    [:input.inline-title-edit
     {:type "text"
      :auto-complete "off"
      :auto-focus true
      :value value
      :on-click #(.stopPropagation %)
      :on-change #(swap! state/*app-state assoc :meets-page/inline-edit-title (.. % -target -value))
      :on-key-down (fn [e]
                     (case (.-key e)
                       "Enter" (do (.stopPropagation e)
                                   (state/update-meet (:id meet) value (:description meet) (:tags meet)
                                     #(swap! state/*app-state dissoc :meets-page/inline-edit-meet :meets-page/inline-edit-title)))
                       "Escape" (do (.stopPropagation e)
                                    (swap! state/*app-state dissoc :meets-page/inline-edit-meet :meets-page/inline-edit-title))
                       nil))
      :on-blur (fn [_]
                 (state/update-meet (:id meet) value (:description meet) (:tags meet)
                   #(swap! state/*app-state dissoc :meets-page/inline-edit-meet :meets-page/inline-edit-title)))}]))

(defn- meet-header [meet is-expanded]
  (let [importance (:importance meet)
        inline-editing? (= (:meets-page/inline-edit-meet @state/*app-state) (:id meet))]
    [:div.item-header
     {:on-click (fn [_]
                  (when-not (or inline-editing?
                                (and is-expanded (not (.. js/window getSelection -isCollapsed))))
                    (state/set-expanded-meet (when-not is-expanded (:id meet)))))}
     [:div.item-title
      [relation-link/relation-link-button :meet (:id meet)]
      (when (and importance (not= importance "normal"))
        [:span.importance-badge {:class importance}
         (case importance "important" "★" "critical" "★★" nil)])
      (if inline-editing?
        [meet-inline-title-edit meet]
        [:span.item-title-text
         {:on-click (fn [e]
                      (when (and is-expanded (.-altKey e))
                        (.stopPropagation e)
                        (swap! state/*app-state assoc
                          :meets-page/inline-edit-meet (:id meet)
                          :meets-page/inline-edit-title (:title meet))))}
         (:title meet)])]
     (when is-expanded
       [:div.item-toolbar
        [meet-date-time-pickers meet]])
     [:div.item-date
      (when (:start_date meet)
        [:span.due-date {:data-tooltip (date/get-day-name (:start_date meet))}
         (str (date/format-date-localized (:start_date meet))
              (when (seq (:start_time meet))
                (str " - " (:start_time meet))))])
      (when (and (:meeting_series_id meet) (not= (:id (state/series-filter)) (:meeting_series_id meet)))
        [:span.recurrence-icon {:on-click (fn [e]
                                            (.stopPropagation e)
                                            (state/set-series-filter {:id (:meeting_series_id meet) :title (:title meet)}))}
         "🔁"])]]))

(defn- meet-categories-readonly [meet]
  [:div.item-tags-readonly
   [task-item/category-badges
    {:item meet
     :category-types [[state/CATEGORY-TYPE-PERSON :people]
                      [state/CATEGORY-TYPE-PLACE :places]
                      [state/CATEGORY-TYPE-PROJECT :projects]
                      [state/CATEGORY-TYPE-GOAL :goals]]
     :toggle-fn state/toggle-shared-filter
     :has-filter-fn state/has-filter-for-type?}]
   (when (seq (:relations meet))
     [relation-badges/relation-badges-collapsed (:relations meet) "met" (:id meet)])])

(defn- meet-item [meet expanded-id people places projects goals]
  (let [is-expanded (= expanded-id (:id meet))]
    [:li {:class (when is-expanded "expanded")}
     [meet-header meet is-expanded]
     (if is-expanded
       [meet-expanded-view meet people places projects goals]
       [meet-categories-readonly meet])]))

(defn- importance-filter-toggle []
  (let [importance-filter (:importance-filter @meets-state/*meets-page-state)]
    [:div.importance-filter-toggle.toggle-group
     [:button {:class (when (nil? importance-filter) "active")
               :on-click #(state/set-meet-importance-filter nil)
               :title (t :importance/filter-off)}
      "○"]
     [:button {:class (str "important" (when (= importance-filter :important) " active"))
               :on-click #(state/set-meet-importance-filter :important)
               :title (t :importance/filter-important)}
      "★"]
     [:button {:class (str "critical" (when (= importance-filter :critical) " active"))
               :on-click #(state/set-meet-importance-filter :critical)
               :title (t :importance/filter-critical)}
      "★★"]]))

(defn- sort-mode-toggle []
  (let [sort-mode (:sort-mode @meets-state/*meets-page-state)]
    [:div.sort-mode-toggle.toggle-group
     [:button {:class (when (= sort-mode :upcoming) "active")
               :on-click #(state/set-meets-sort-mode :upcoming)}
      (t :meets/upcoming)]
     [:button {:class (when (= sort-mode :past) "active")
               :on-click #(state/set-meets-sort-mode :past)}
      (t :meets/past)]]))

(defn- search-add-form []
  (let [input-value (:filter-search @meets-state/*meets-page-state)]
    [:div.combined-search-add-form
     [:input#meets-filter-search
      {:type "text"
       :auto-complete "off"
       :placeholder (t :meets/search-or-add)
       :value input-value
       :on-change #(state/set-meet-filter-search (-> % .-target .-value))
       :on-key-down (fn [e]
                      (cond
                        (and (= (.-key e) "Enter") (seq input-value))
                        (do
                          (.preventDefault e)
                          (state/add-meet input-value
                                          #(state/set-meet-filter-search "")))

                        (= (.-key e) "Escape")
                        (state/set-meet-filter-search "")))}]
     [:button {:on-click #(when (seq input-value)
                            (state/add-meet input-value
                                            (fn [] (state/set-meet-filter-search ""))))}
      (t :tasks/add-button)]
     (when (seq input-value)
       [:button.clear-search {:on-click #(state/set-meet-filter-search "")} "x"])]))

(defn- meets-filter-section [{:keys [title filter-key items selected-ids toggle-fn clear-fn collapsed?]}]
  [filter-section/category-filter-section {:title title
                                           :shortcut-number (meets-category-shortcut-numbers filter-key)
                                           :filter-key filter-key
                                           :items items
                                           :marked-ids selected-ids
                                           :toggle-fn toggle-fn
                                           :clear-fn clear-fn
                                           :collapsed? collapsed?
                                           :toggle-collapsed-fn state/toggle-meets-filter-collapsed
                                           :set-search-fn state/set-meets-category-search
                                           :search-state-path [:meets-page/category-search filter-key]
                                           :section-class (name filter-key)
                                           :item-active-class "active"
                                           :label-class nil
                                           :page-prefix "meets"}])

(def ^:private meets-sidebar-filter-configs
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
    :filter-state-key :meets-page/filter-goals
    :category-type state/CATEGORY-TYPE-GOAL}])

(defn- sidebar-filters []
  (let [app-state @state/*app-state
        collapsed-filters (:meets-page/collapsed-filters app-state)]
    (into [:div.sidebar]
          (for [{:keys [filter-key title-key items-key filter-state-key category-type]} meets-sidebar-filter-configs]
            [meets-filter-section {:title (t title-key)
                                   :filter-key filter-key
                                   :items (get app-state items-key)
                                   :selected-ids (get app-state filter-state-key)
                                   :toggle-fn (if (= category-type state/CATEGORY-TYPE-GOAL)
                                                #(state/toggle-meets-goal-filter %)
                                                #(state/toggle-shared-filter category-type %))
                                   :clear-fn (if (= category-type state/CATEGORY-TYPE-GOAL)
                                               #(state/clear-meets-goal-filter)
                                               #(state/clear-shared-filter category-type))
                                   :collapsed? (contains? collapsed-filters filter-key)}]))))

(defn- series-scope-selector [series]
  (let [scope (or (:scope series) "both")]
    [:div.task-scope-selector.toggle-group.compact
     (for [s ["private" "both" "work"]]
       ^{:key s}
       [:button.toggle-option
        {:class (when (= scope s) "active")
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-meeting-series-scope (:id series) s))}
        s])]))

(defn- series-category-selector [series category-type entities label]
  (let [current-categories (case category-type
                             state/CATEGORY-TYPE-PERSON (:people series)
                             state/CATEGORY-TYPE-PLACE (:places series)
                             state/CATEGORY-TYPE-PROJECT (:projects series)
                             state/CATEGORY-TYPE-GOAL (:goals series)
                             [])]
    [category-selector/category-selector
     {:entity series
      :entity-id-key :id
      :category-type category-type
      :entities entities
      :label label
      :current-categories current-categories
      :on-categorize #(state/categorize-meeting-series (:id series) category-type %)
      :on-uncategorize #(state/uncategorize-meeting-series (:id series) category-type %)
      :on-close-focus-fn nil
      :open-selector-state (:category-selector/open @state/*app-state)
      :search-state (:category-selector/search @state/*app-state)
      :open-selector-fn state/open-category-selector
      :close-selector-fn state/close-category-selector
      :set-search-fn state/set-category-selector-search}]))

(defn- series-expanded-view [series people places projects goals]
  [:div.item-details
   (if (seq (:description series))
     [task-item/clampable-description
      {:text (:description series)
       :on-click #(state/set-editing-modal :meeting-series series)}]
     [:button.edit-icon.description-placeholder
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (state/set-editing-modal :meeting-series series))}
      "✎"])
   [:div.item-tags
    [series-category-selector series state/CATEGORY-TYPE-PERSON people (t :category/person)]
    [series-category-selector series state/CATEGORY-TYPE-PLACE places (t :category/place)]
    [series-category-selector series state/CATEGORY-TYPE-PROJECT projects (t :category/project)]
    [series-category-selector series state/CATEGORY-TYPE-GOAL goals (t :category/goal)]]
   [:div.item-actions
    [series-scope-selector series]
    [:div.combined-button-wrapper
     [:button.delete-btn {:on-click #(state/set-confirm-delete-series series)}
      (t :task/delete)]]]])

(defn- series-inline-title-edit [series]
  (let [value (or (:series-page/inline-edit-title @state/*app-state) "")]
    [:input.inline-title-edit
     {:type "text"
      :auto-complete "off"
      :auto-focus true
      :value value
      :on-click #(.stopPropagation %)
      :on-change #(swap! state/*app-state assoc :series-page/inline-edit-title (.. % -target -value))
      :on-key-down (fn [e]
                     (case (.-key e)
                       "Enter" (do (.stopPropagation e)
                                   (state/update-meeting-series (:id series) value (:description series) (:tags series)
                                     #(swap! state/*app-state dissoc :series-page/inline-edit-series :series-page/inline-edit-title)))
                       "Escape" (do (.stopPropagation e)
                                    (swap! state/*app-state dissoc :series-page/inline-edit-series :series-page/inline-edit-title))
                       nil))
      :on-blur (fn [_]
                 (state/update-meeting-series (:id series) value (:description series) (:tags series)
                   #(swap! state/*app-state dissoc :series-page/inline-edit-series :series-page/inline-edit-title)))}]))

(defn- series-header [series is-expanded]
  (let [inline-editing? (= (:series-page/inline-edit-series @state/*app-state) (:id series))]
    [:div.item-header
     {:on-click (fn [_]
                  (when-not (or inline-editing?
                                (and is-expanded (not (.. js/window getSelection -isCollapsed))))
                    (state/set-expanded-series (when-not is-expanded (:id series)))))}
     [:div.item-title
      (if inline-editing?
        [series-inline-title-edit series]
        [:span.item-title-text
         {:on-click (fn [e]
                      (when (and is-expanded (.-altKey e))
                        (.stopPropagation e)
                        (swap! state/*app-state assoc
                          :series-page/inline-edit-series (:id series)
                          :series-page/inline-edit-title (:title series))))}
         (:title series)])]
     (when is-expanded
       [:div.item-toolbar
        [:button.calendar-icon {:on-click (fn [e]
                                            (.stopPropagation e)
                                            (state/set-editing-modal :meeting-series series :scheduling))}
         "📅"]])
     [:button.series-filter-btn {:on-click (fn [e]
                                             (.stopPropagation e)
                                             (state/set-series-filter series))
                                  :title (t :meets/filter-by-series)}
      "⏚"]]))

(defn- series-categories-readonly [series]
  [:div.item-tags-readonly
   [task-item/category-badges
    {:item series
     :category-types [[state/CATEGORY-TYPE-PERSON :people]
                      [state/CATEGORY-TYPE-PLACE :places]
                      [state/CATEGORY-TYPE-PROJECT :projects]
                      [state/CATEGORY-TYPE-GOAL :goals]]
     :toggle-fn state/toggle-shared-filter
     :has-filter-fn state/has-filter-for-type?}]])

(defn- series-create-meeting-button [series]
  (let [has-schedule? (and (:schedule_days series) (not= (:schedule_days series) ""))]
    [:button.create-next-meeting-btn
     (cond-> {:class (when-not has-schedule? "disabled")
              :disabled (not has-schedule?)
              :on-click (fn [e]
                          (.stopPropagation e)
                          (when has-schedule?
                            (state/open-create-date-modal :meeting-series series)))}
       (not has-schedule?) (assoc :title (t :meets/create-next-disabled-no-schedule)))
     (t :meets/create-meeting)]))

(defn- series-item [series expanded-id people places projects goals]
  (let [is-expanded (= expanded-id (:id series))]
    [:li {:class (when is-expanded "expanded")}
     [series-header series is-expanded]
     (if is-expanded
       [series-expanded-view series people places projects goals]
       [:<>
        [series-categories-readonly series]
        [series-create-meeting-button series]])]))

(defn- series-search-add-form []
  (let [input-value (:filter-search @meeting-series-state/*meeting-series-page-state)]
    [:div.combined-search-add-form
     [:input#meets-filter-search
      {:type "text"
       :auto-complete "off"
       :placeholder (t :meets/search-or-add-series)
       :value input-value
       :on-change #(state/set-meeting-series-filter-search (-> % .-target .-value))
       :on-key-down (fn [e]
                      (cond
                        (and (= (.-key e) "Enter") (seq input-value))
                        (do
                          (.preventDefault e)
                          (state/add-meeting-series input-value
                                                    #(state/set-meeting-series-filter-search "")))

                        (= (.-key e) "Escape")
                        (state/set-meeting-series-filter-search "")))}]
     [:button {:on-click #(when (seq input-value)
                            (state/add-meeting-series input-value
                                                      (fn [] (state/set-meeting-series-filter-search ""))))}
      (t :tasks/add-button)]
     (when (seq input-value)
       [:button.clear-search {:on-click #(state/set-meeting-series-filter-search "")} "x"])]))

(defn- series-filter-bar []
  (let [series-filter (state/series-filter)
        summary-mode? (:meets-page/meet-summary-mode @state/*app-state)]
    [:div.series-filter-bar
     [:span.series-filter-label (:title series-filter)]
     [:button.journal-summary-btn
      {:class (when summary-mode? "active")
       :on-click #(state/toggle-meet-summary-mode)}
      "📋"]
     [:button.clear-search {:on-click #(state/clear-series-filter)} "x"]]))

(defn- meets-summary []
  (let [meets (:meets @state/*app-state)]
    [:div.journal-entries-summary
     (for [meet meets]
       ^{:key (:id meet)}
       [:div.journal-entry-summary-item
        [:div.journal-entry-summary-header
         [:span.journal-entry-summary-title (:title meet)]
         (when (:start_date meet)
           [:span.journal-entry-summary-date
            (str (date/format-date-localized (:start_date meet))
                 (when (seq (:start_time meet))
                   (str " - " (:start_time meet))))])]
        (when (seq (:description meet))
          [:div.journal-entry-summary-description
           {:on-click #(state/set-editing-modal :meet meet)}
           (:description meet)])])]))

(defn- series-toggle []
  (let [series-mode (:meets-page/series-mode @state/*app-state)]
    [:div.series-mode-toggle.toggle-group
     [:button {:class (when series-mode "active")
               :on-click #(state/toggle-series-mode)}
      (t :meets/series)]]))

(defn meets-tab []
  (let [{:keys [meets meeting-series people places projects goals]} @state/*app-state
        series-mode (state/series-mode?)
        series-filter (state/series-filter)
        summary-mode? (:meets-page/meet-summary-mode @state/*app-state)
        {:keys [expanded-meet]} @meets-state/*meets-page-state
        {:keys [expanded-series]} @meeting-series-state/*meeting-series-page-state]
    [:div.main-layout
     [sidebar-filters]
     [:div.main-content.meets-page
      [:div.tasks-header
       [series-toggle]
       (when-not series-mode
         [importance-filter-toggle])
       (when-not (or series-mode (and series-filter summary-mode?))
         [sort-mode-toggle])]
      (cond
        series-mode [series-search-add-form]
        series-filter [series-filter-bar]
        :else [search-add-form])
      (cond
        series-mode
        (if (empty? meeting-series)
          [:p.empty-message (t :meets/no-series)]
          [:ul.items
           (for [s meeting-series]
             ^{:key (:id s)}
             [series-item s expanded-series people places projects goals])])

        (and series-filter summary-mode?)
        [meets-summary]

        :else
        (if (empty? meets)
          [:p.empty-message (t :meets/no-meets)]
          [:ul.items
           (for [meet meets]
             ^{:key (:id meet)}
             [meet-item meet expanded-meet people places projects goals])]))]]))

