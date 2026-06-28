(ns et.tr.ui.views.reports
  (:require [et.tr.ui.state :as state]
            [et.tr.ui.state.reports :as reports-state]
            [et.tr.ui.date :as date]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.ui.components.item-card :as item-card]
            [et.tr.ui.components.filter-section :as filter-section]
            [et.tr.i18n :as i18n :refer [t]]))

(def ^:private reports-category-shortcut-keys
  {"Digit1" :people
   "Digit2" :places
   "Digit3" :projects
   "Digit4" :goals})

(def reports-category-shortcut-numbers
  (into {} (map (fn [[k v]] [v (subs k 5)]) reports-category-shortcut-keys)))

(defn get-reports-category-shortcut-keys []
  reports-category-shortcut-keys)

(defn- reports-filter-section [{:keys [title filter-key items selected-ids toggle-fn clear-fn collapsed?]}]
  [filter-section/category-filter-section {:title title
                                           :shortcut-number (reports-category-shortcut-numbers filter-key)
                                           :filter-key filter-key
                                           :items items
                                           :marked-ids selected-ids
                                           :toggle-fn toggle-fn
                                           :clear-fn clear-fn
                                           :collapsed? collapsed?
                                           :toggle-collapsed-fn state/toggle-reports-filter-collapsed
                                           :set-search-fn state/set-reports-category-search
                                           :search-state-path [:reports-page/category-search filter-key]
                                           :section-class (name filter-key)
                                           :item-active-class "active"
                                           :label-class nil
                                           :page-prefix "reports"}])

(def ^:private reports-sidebar-filter-configs
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

(defn- items-filter-toggle []
  (let [items-filter (:reports-page/items-filter @state/*app-state)]
    [:div.items-filter-toggle.toggle-group
     [:button {:class (when (= items-filter :all) "active")
               :on-click #(when (not= items-filter :all) (state/set-reports-items-filter :all))}
      (t :reports/filter-all)]
     [:button {:class (when (= items-filter :tasks-meets) "active")
               :on-click #(when (not= items-filter :tasks-meets) (state/set-reports-items-filter :tasks-meets))}
      (t :reports/filter-tasks-meets)]
     [:button {:class (when (= items-filter :journals) "active")
               :on-click #(when (not= items-filter :journals) (state/set-reports-items-filter :journals))}
      (t :reports/filter-journals)]]))

(defn- journals-summary-toggle []
  (let [summary-mode? (:reports-page/journals-summary-mode @state/*app-state)]
    [:button.journal-summary-btn
     {:class (when summary-mode? "active")
      :on-click #(state/toggle-reports-journals-summary-mode)}
     "📋"]))

(defn- journal-entries-summary [journal-entries]
  [:div.journal-entries-summary
   (for [entry (sort-by :entry_date #(compare %2 %1) journal-entries)]
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

(defn- sidebar-filters []
  (let [app-state @state/*app-state
        collapsed-filters (:reports-page/collapsed-filters app-state)]
    (into [:div.sidebar [filter-section/category-badge-toggle]]
          (for [{:keys [filter-key title-key items-key filter-state-key category-type]} reports-sidebar-filter-configs]
            [reports-filter-section {:title (t title-key)
                                     :filter-key filter-key
                                     :items (get app-state items-key)
                                     :selected-ids (get app-state filter-state-key)
                                     :toggle-fn #(state/toggle-shared-filter category-type %)
                                     :clear-fn #(state/clear-shared-filter category-type)
                                     :collapsed? (contains? collapsed-filters filter-key)}]))))

(defn- extract-date [date-str]
  (when date-str
    (.substring date-str 0 10)))

(defn- task-actions [task]
  [:div.combined-button-wrapper
   [:button.combined-main-btn.delete-btn {:on-click #(state/set-confirm-delete-task task)}
    (t :task/delete)]
   [:button.combined-dropdown-btn.delete-btn
    {:on-click #(state/set-reports-task-dropdown-open (:id task))}
    "▼"]
   (when (= (:id task) (:reports-task-dropdown-open @state/*app-state))
     [:div.task-dropdown-menu
      [:button.dropdown-item
       {:on-click #(do
                     (state/set-reports-task-dropdown-open nil)
                     (state/open-done-date-modal task))}
       (t :task/change-done-date)]])])

(defn- report-task-item [task]
  (let [is-expanded (= (:expanded-task @reports-state/*reports-page-state) (:id task))]
    [item-card/item-card
     {:item task
      :expanded? is-expanded
      :on-toggle #(swap! reports-state/*reports-page-state assoc :expanded-task
                         (when-not is-expanded (:id task)))
      :container {:tag :li :class "report-item report-task"}
      :title-icon "☑"
      :relation-link [:task (:id task)]
      :inline-edit (item-card/make-inline-edit
                     {:edit-id-path :reports-page/inline-edit-task
                      :title-path :reports-page/inline-edit-title
                      :update-fn state/update-task})
      :description {:edit-type :task}
      :categories {:selector-fn task-item/category-selector
                   :relations-prefix "tsk"
                   :readonly-fn (fn [t] [task-item/task-categories-readonly t])}
      :footer {:left [{:type :scope :value (:scope task)
                       :on-set #(state/set-task-scope (:id task) %)}]
               :right [{:type :custom :render [task-actions task]}]}}]))

(defn- report-meet-item [meet]
  (let [is-expanded (= (:expanded-meet @reports-state/*reports-page-state) (:id meet))]
    [item-card/item-card
     {:item meet
      :expanded? is-expanded
      :on-toggle #(swap! reports-state/*reports-page-state assoc :expanded-meet
                         (when-not is-expanded (:id meet)))
      :container {:tag :li :class "report-item report-meet"}
      :title-icon "🗓️"
      :relation-link [:meet (:id meet)]
      :inline-edit (item-card/make-inline-edit
                     {:edit-id-path :reports-page/inline-edit-meet
                      :title-path :reports-page/inline-edit-meet-title
                      :update-fn state/update-meet})
      :badges {:importance? true}
      :date {:render (fn [m]
                       (when (seq (:start_time m))
                         [:span.due-date (:start_time m)]))}
      :description {:edit-type :meet}
      :categories {:selector-fn task-item/meet-category-selector
                   :relations-prefix "met"}
      :footer {:left [{:type :scope :value (:scope meet)
                       :on-set #(state/set-meet-scope (:id meet) %)}]
               :right [{:type :delete :on-click #(state/set-confirm-delete-meet meet)}]}}]))

(defn- report-journal-entry-item [entry]
  (let [is-expanded (= (:expanded-journal-entry @reports-state/*reports-page-state) (:id entry))
        journals-only? (= :journals (:reports-page/items-filter @state/*app-state))]
    [item-card/item-card
     {:item entry
      :expanded? is-expanded
      :on-toggle #(swap! reports-state/*reports-page-state assoc :expanded-journal-entry
                         (when-not is-expanded (:id entry)))
      :container {:tag :li :class "report-item report-journal-entry"}
      :title-icon (when-not journals-only? "📝")
      :relation-link [:journal-entry (:id entry)]
      :inline-edit (item-card/make-inline-edit
                     {:edit-id-path :reports-page/inline-edit-journal-entry
                      :title-path :reports-page/inline-edit-journal-entry-title
                      :update-fn state/update-journal-entry})
      :date (when (:journal_id entry)
              {:render (fn [e]
                         [:span.recurrence-icon {:on-click (fn [ev]
                                                             (.stopPropagation ev)
                                                             (state/set-journal-filter {:id (:journal_id e) :title (:title e)})
                                                             (state/set-active-tab :resources))}
                          "🔁"])})
      :description {:edit-type :journal-entry}
      :categories {:selector-fn task-item/journal-entry-category-selector
                   :relations-prefix "jen"}
      :footer {:left [{:type :scope :value (:scope entry)
                       :on-set #(state/set-journal-entry-scope (:id entry) %)}]
               :right [{:type :delete :on-click #(state/set-confirm-delete-journal-entry entry)}]}}]))

(defn- day-section [day-date day-tasks day-meets day-entries]
  [:div.report-day-group {:key day-date}
   [:h4.done-day-header (date/day-formatted day-date)]
   (when (seq day-entries)
     (into [:ul.items] (map report-journal-entry-item day-entries)))
   (when (seq day-tasks)
     (into [:ul.items] (map report-task-item day-tasks)))
   (when (seq day-meets)
     (into [:ul.items] (map report-meet-item day-meets)))])

(defn- week-section [week-key week-dates tasks-by-day meets-by-day daily-entries-by-day weekly-entries]
  (let [[_ week-num] week-key]
    [:div.report-week-group {:key (str (first week-key) "-" (second week-key))}
     [:h3.report-week-header (i18n/tf :reports/week week-num)]
     (when (seq weekly-entries)
       [:div.report-weekly-journals
        (into [:ul.items] (map report-journal-entry-item weekly-entries))])
     (for [d week-dates]
       ^{:key d}
       [day-section d
        (get tasks-by-day d)
        (get meets-by-day d)
        (get daily-entries-by-day d)])]))

(defn reports-tab []
  (let [{:keys [reports-data]} @state/*app-state
        items-filter (:reports-page/items-filter @state/*app-state)
        summary-mode? (and (= items-filter :journals)
                           (:reports-page/journals-summary-mode @state/*app-state))
        tasks (:tasks reports-data)
        meets (:meets reports-data)
        journal-entries (:journal_entries reports-data)
        daily-entries (filter #(not= (:schedule_type %) "weekly") journal-entries)
        weekly-entries (filter #(= (:schedule_type %) "weekly") journal-entries)
        tasks-by-day (group-by #(extract-date (or (:done_at %) (:modified_at %))) tasks)
        meets-by-day (group-by #(extract-date (:start_date %)) meets)
        daily-entries-by-day (group-by :entry_date daily-entries)
        weekly-entries-by-week (group-by #(date/iso-week-key (:entry_date %)) weekly-entries)
        all-dates (->> (concat (keys tasks-by-day) (keys meets-by-day) (keys daily-entries-by-day))
                       (filter some?)
                       distinct)
        dates-by-week (group-by date/iso-week-key all-dates)
        weekly-only-weeks (->> (keys weekly-entries-by-week)
                               (remove #(contains? dates-by-week %)))
        all-week-keys (->> (concat (keys dates-by-week) weekly-only-weeks)
                           distinct
                           (sort #(compare %2 %1)))
        sorted-dates-by-week (into {} (map (fn [[k dates]] [k (sort #(compare %2 %1) dates)]) dates-by-week))]
    [:div.main-layout
     [sidebar-filters]
     [:div.main-content.reports-page
      [:div.tasks-header
       [items-filter-toggle]
       (when (= items-filter :journals)
         [journals-summary-toggle])]
      (cond
        summary-mode?
        (if (empty? journal-entries)
          [:p.empty-message (t :reports/no-data)]
          [journal-entries-summary journal-entries])

        (empty? all-week-keys)
        [:p.empty-message (t :reports/no-data)]

        :else
        [:<>
         (into [:div.report-weeks]
           (for [wk all-week-keys]
             ^{:key (str (first wk) "-" (second wk))}
             [week-section wk
              (get sorted-dates-by-week wk [])
              tasks-by-day meets-by-day daily-entries-by-day
              (get weekly-entries-by-week wk)]))
         (when (:has-more? @reports-state/*reports-page-state)
           [:div.load-more
            [:button.load-more-btn {:on-click #(state/load-more-reports)}
             (t :reports/see-more)]])])]]))
