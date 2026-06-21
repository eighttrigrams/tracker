(ns et.tr.ui.views.meets
  (:require [et.tr.ui.state :as state]
            [et.tr.ui.state.meets :as meets-state]
            [et.tr.ui.state.meeting-series :as meeting-series-state]
            [et.tr.ui.date :as date]
            [et.tr.ui.components.item-card :as item-card]
            [et.tr.ui.components.filter-section :as filter-section]
            [et.tr.ui.components.category-selector :as category-selector]
            [et.tr.i18n :as i18n :refer [t]]))

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

(defn- meet-date-render [meet]
  [:<>
   (when (:start_date meet)
     [:span.due-date {:data-tooltip (date/get-day-name (:start_date meet))}
      (str (date/format-date-localized (:start_date meet))
           (when (seq (:start_time meet))
             (str " - " (:start_time meet))))])
   (when (and (:meeting_series_id meet) (not= (:id (state/series-filter)) (:meeting_series_id meet)))
     [:span.recurrence-icon {:on-click (fn [e]
                                         (.stopPropagation e)
                                         (state/set-series-filter {:id (:meeting_series_id meet) :title (:title meet)}))}
      "🔁"])])

(defn- meet-item [meet expanded-id people places projects goals]
  (let [is-expanded (= expanded-id (:id meet))]
    [item-card/item-card
     {:item meet
      :expanded? is-expanded
      :on-toggle #(state/set-expanded-meet (when-not is-expanded (:id meet)))
      :relation-link [:meet (:id meet)]
      :inline-edit (item-card/make-inline-edit
                     {:edit-id-path :meets-page/inline-edit-meet
                      :title-path :meets-page/inline-edit-title
                      :update-fn state/update-meet})
      :badges {:importance? true}
      :toolbar {:calendar {:on-click #(state/set-editing-modal :meet meet :time)}}
      :date {:render meet-date-render}
      :description {:edit-type :meet}
      :categories {:selector-fn meet-category-selector :relations-prefix "met"}
      :footer {:left [{:type :scope :value (:scope meet)
                       :on-set #(state/set-meet-scope (:id meet) %)}
                      {:type :importance :value (:importance meet)
                       :on-set #(state/set-meet-importance (:id meet) %)}]
               :right [{:type :delete :on-click #(state/set-confirm-delete-meet meet)}]}}]))

(defn- meet-week-section [week-key week-meets expanded-meet people places projects goals]
  (let [[_ week-num] week-key]
    [:div.report-week-group {:key (str (first week-key) "-" (second week-key))}
     [:h3.report-week-header (i18n/tf :meets/week week-num)]
     (into [:ul.items]
           (map (fn [meet]
                  ^{:key (:id meet)}
                  [meet-item meet expanded-meet people places projects goals])
                week-meets))]))

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
    :filter-state-key :shared/filter-goals
    :category-type state/CATEGORY-TYPE-GOAL}])

(defn- sidebar-filters []
  (let [app-state @state/*app-state
        collapsed-filters (:meets-page/collapsed-filters app-state)]
    (into [:div.sidebar [filter-section/category-badge-toggle]]
          (for [{:keys [filter-key title-key items-key filter-state-key category-type]} meets-sidebar-filter-configs]
            [meets-filter-section {:title (t title-key)
                                   :filter-key filter-key
                                   :items (get app-state items-key)
                                   :selected-ids (get app-state filter-state-key)
                                   :toggle-fn #(state/toggle-shared-filter category-type %)
                                   :clear-fn #(state/clear-shared-filter category-type)
                                   :collapsed? (contains? collapsed-filters filter-key)}]))))

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

(defn- series-create-meeting-button [series]
  [:button.create-next-meeting-btn
   {:on-click (fn [e]
                (.stopPropagation e)
                (state/open-create-date-modal :meeting-series series))}
   (t :meets/create-meeting)])

(defn- series-item [series expanded-id people places projects goals]
  (let [is-expanded (= expanded-id (:id series))]
    [item-card/item-card
     {:item series
      :expanded? is-expanded
      :on-toggle #(state/set-expanded-series (when-not is-expanded (:id series)))
      :inline-edit (item-card/make-inline-edit
                     {:edit-id-path :series-page/inline-edit-series
                      :title-path :series-page/inline-edit-title
                      :update-fn state/update-meeting-series})
      :toolbar {:calendar {:on-click #(state/set-editing-modal :meeting-series series :scheduling)}}
      :header-extra [:button.series-filter-btn
                     {:on-click (fn [e]
                                  (.stopPropagation e)
                                  (state/set-series-filter series))
                      :title (t :meets/filter-by-series)}
                     "⏚"]
      :description {:edit-type :meeting-series}
      :categories {:selector-fn series-category-selector}
      :footer {:left [{:type :scope :value (:scope series)
                       :on-set #(state/set-meeting-series-scope (:id series) %)}]
               :right [{:type :delete :on-click #(state/set-confirm-delete-series series)}]}
      :readonly-extra [series-create-meeting-button series]}]))

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
     [:span.series-filter-label
      {:on-click #(state/open-filter-target-edit-modal :meeting-series "/api/meeting-series/" (:id series-filter))
       :style {:cursor "pointer"}}
      (:title series-filter)]
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
          (let [sort-mode (:sort-mode @meets-state/*meets-page-state)
                meets-by-week (group-by #(date/iso-week-key (:start_date %)) meets)
                week-keys (->> (keys meets-by-week)
                               (filter some?)
                               distinct
                               (sort (if (= sort-mode :past)
                                       #(compare %2 %1)
                                       compare)))]
            [:<>
             (into [:div.report-weeks]
                   (for [wk week-keys]
                     ^{:key (str (first wk) "-" (second wk))}
                     [meet-week-section wk (get meets-by-week wk) expanded-meet people places projects goals]))
             (when (:has-more? @meets-state/*meets-page-state)
               [:div.load-more
                [:button.load-more-btn {:on-click #(state/load-more-meets)}
                 (t :meets/see-more)]])])))]]))
