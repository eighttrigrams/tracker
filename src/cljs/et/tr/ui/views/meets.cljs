(ns et.tr.ui.views.meets
  (:require [et.tr.ui.state :as state]
            [et.tr.ui.state.meets :as meets-state]
            [et.tr.ui.date :as date]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.ui.components.filter-section :as filter-section]
            [et.tr.ui.components.category-selector :as category-selector]
            [et.tr.ui.components.relation-link :as relation-link]
            [et.tr.ui.components.relation-badges :as relation-badges]
            [et.tr.i18n :refer [t]]))

(def ^:private meets-category-shortcut-keys
  {"Digit1" :people
   "Digit2" :places
   "Digit3" :projects})

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
  [:div.meet-date-time-pickers
   [:span.date-picker-wrapper
    {:on-click #(.stopPropagation %)}
    [:input.date-picker-input
     {:type "date"
      :value (or (:start_date meet) "")
      :on-change (fn [e]
                   (let [v (.. e -target -value)]
                     (state/set-meet-start-date (:id meet) (when (seq v) v))))}]
    [:button.calendar-icon {:on-click (fn [e]
                                        (.stopPropagation e)
                                        (-> e .-currentTarget .-parentElement (.querySelector "input") .showPicker))}
     "📅"]]
   [:span.time-picker-wrapper
    {:on-click #(.stopPropagation %)}
    [:input.time-picker-input
     {:type "time"
      :value (or (:start_time meet) "")
      :on-change (fn [e]
                   (let [v (.. e -target -value)]
                     (state/set-meet-start-time (:id meet) (when (seq v) v))))}]
    [:button.clock-icon {:on-click (fn [e]
                                     (.stopPropagation e)
                                     (-> e .-currentTarget .-parentElement (.querySelector "input") .showPicker))}
     "🕐"]]])

(defn- meet-expanded-view [meet people places projects]
  [:div.item-details
   (when (seq (:description meet))
     [:div.item-description [task-item/markdown (:description meet)]])
   [meet-date-time-pickers meet]
   [:div.item-tags
    [meet-category-selector meet state/CATEGORY-TYPE-PERSON people (t :category/person)]
    [meet-category-selector meet state/CATEGORY-TYPE-PLACE places (t :category/place)]
    [meet-category-selector meet state/CATEGORY-TYPE-PROJECT projects (t :category/project)]
    [relation-badges/relation-badges-expanded (:relations meet) "met" (:id meet)]]
   [:div.item-actions
    [meet-scope-selector meet]
    [meet-importance-selector meet]
    [:div.combined-button-wrapper
     [:button.delete-btn {:on-click #(state/set-confirm-delete-meet meet)}
      (t :task/delete)]]]])

(defn- meet-header [meet is-expanded]
  (let [importance (:importance meet)]
    [:div.item-header
     {:on-click #(state/set-expanded-meet (when-not is-expanded (:id meet)))}
     [:div.item-title
      [relation-link/relation-link-button :meet (:id meet)]
      (when (and importance (not= importance "normal"))
        [:span.importance-badge {:class importance}
         (case importance "important" "★" "critical" "★★" nil)])
      (when (seq (:start_time meet))
        [:span.task-time (:start_time meet)])
      (:title meet)
      (when is-expanded
        [:button.edit-icon {:on-click (fn [e]
                                        (.stopPropagation e)
                                        (state/set-editing-modal :meet meet))}
         "✎"])]
     [:div.item-date
      (when (:start_date meet)
        [:span.due-date {:data-tooltip (date/get-day-name (:start_date meet))}
         (date/format-date-localized (:start_date meet))])]]))

(defn- meet-categories-readonly [meet]
  [task-item/category-badges
   {:item meet
    :category-types [[state/CATEGORY-TYPE-PERSON :people]
                     [state/CATEGORY-TYPE-PLACE :places]
                     [state/CATEGORY-TYPE-PROJECT :projects]]
    :toggle-fn state/toggle-shared-filter
    :has-filter-fn state/has-filter-for-type?}])

(defn- meet-item [meet expanded-id people places projects]
  (let [is-expanded (= expanded-id (:id meet))]
    [:li {:class (when is-expanded "expanded")}
     [meet-header meet is-expanded]
     (if is-expanded
       [meet-expanded-view meet people places projects]
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
                        (and (.-altKey e) (= (.-key e) "Enter") (seq input-value))
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
    :category-type state/CATEGORY-TYPE-PROJECT}])

(defn- sidebar-filters []
  (let [app-state @state/*app-state
        collapsed-filters (:meets-page/collapsed-filters app-state)]
    (into [:div.sidebar]
          (for [{:keys [filter-key title-key items-key filter-state-key category-type]} meets-sidebar-filter-configs]
            [meets-filter-section {:title (t title-key)
                                   :filter-key filter-key
                                   :items (get app-state items-key)
                                   :selected-ids (get app-state filter-state-key)
                                   :toggle-fn #(state/toggle-shared-filter category-type %)
                                   :clear-fn #(state/clear-shared-filter category-type)
                                   :collapsed? (contains? collapsed-filters filter-key)}]))))

(defn meets-tab []
  (let [{:keys [meets people places projects]} @state/*app-state
        {:keys [expanded-meet]} @meets-state/*meets-page-state]
    [:div.main-layout
     [sidebar-filters]
     [:div.main-content.meets-page
      [:div.tasks-header
       [:h2 (t :meets/heading)]
       [importance-filter-toggle]
       [sort-mode-toggle]]
      [search-add-form]
      (if (empty? meets)
        [:p.empty-message (t :meets/no-meets)]
        [:ul.items
         (for [meet meets]
           ^{:key (:id meet)}
           [meet-item meet expanded-meet people places projects])])]]))
