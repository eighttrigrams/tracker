(ns et.tr.ui.views.reports
  (:require [et.tr.ui.state :as state]
            [et.tr.ui.state.reports :as reports-state]
            [et.tr.ui.date :as date]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.ui.components.filter-section :as filter-section]
            [et.tr.ui.components.relation-link :as relation-link]
            [et.tr.ui.components.relation-badges :as relation-badges]
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
    :filter-state-key :reports-page/filter-goals
    :category-type state/CATEGORY-TYPE-GOAL}])

(defn- sidebar-filters []
  (let [app-state @state/*app-state
        collapsed-filters (:reports-page/collapsed-filters app-state)]
    (into [:div.sidebar]
          (for [{:keys [filter-key title-key items-key filter-state-key category-type]} reports-sidebar-filter-configs]
            [reports-filter-section {:title (t title-key)
                                     :filter-key filter-key
                                     :items (get app-state items-key)
                                     :selected-ids (get app-state filter-state-key)
                                     :toggle-fn (if (= category-type state/CATEGORY-TYPE-GOAL)
                                                  #(state/toggle-reports-goal-filter %)
                                                  #(state/toggle-shared-filter category-type %))
                                     :clear-fn (if (= category-type state/CATEGORY-TYPE-GOAL)
                                                 #(state/clear-reports-goal-filter)
                                                 #(state/clear-shared-filter category-type))
                                     :collapsed? (contains? collapsed-filters filter-key)}]))))

(defn- extract-date [date-str]
  (when date-str
    (.substring date-str 0 10)))

(defn- report-task-item [task]
  (let [is-expanded (= (:expanded-task @reports-state/*reports-page-state) (:id task))]
    [:li {:class (str "report-item report-task" (when is-expanded " expanded"))}
     [:div.item-header
      {:on-click #(swap! reports-state/*reports-page-state assoc :expanded-task
                         (when-not is-expanded (:id task)))}
      [:div.item-title
       [relation-link/relation-link-button :task (:id task)]
       [:span.item-title-text (:title task)]]
      (when is-expanded
        [:div.item-toolbar
         [:button.edit-icon {:on-click (fn [e]
                                         (.stopPropagation e)
                                         (state/set-editing-modal :task task))}
          "✎"]])]
     (if is-expanded
       [:div.item-details
        (when (seq (:description task))
          [task-item/clampable-description
           {:text (:description task)
            :on-click #(state/set-editing-modal :task task)}])
        [relation-badges/relation-badges-expanded (:relations task) "tsk" (:id task)]]
       [task-item/task-categories-readonly task])]))

(defn- report-meet-item [meet]
  (let [is-expanded (= (:expanded-meet @reports-state/*reports-page-state) (:id meet))]
    [:li {:class (str "report-item report-meet" (when is-expanded " expanded"))}
     [:div.item-header
      {:on-click #(swap! reports-state/*reports-page-state assoc :expanded-meet
                         (when-not is-expanded (:id meet)))}
      [:div.item-title
       [relation-link/relation-link-button :meet (:id meet)]
       (when (and (:importance meet) (not= (:importance meet) "normal"))
         [:span.importance-badge {:class (:importance meet)}
          (case (:importance meet) "important" "★" "critical" "★★" nil)])
       [:span.item-title-text (:title meet)]]
      (when is-expanded
        [:div.item-toolbar
         [:button.edit-icon {:on-click (fn [e]
                                         (.stopPropagation e)
                                         (state/set-editing-modal :meet meet))}
          "✎"]])
      [:div.item-date
       (when (seq (:start_time meet))
         [:span.due-date (:start_time meet)])]]
     (if is-expanded
       [:div.item-details
        (when (seq (:description meet))
          [task-item/clampable-description
           {:text (:description meet)
            :on-click #(state/set-editing-modal :meet meet)}])
        [relation-badges/relation-badges-expanded (:relations meet) "met" (:id meet)]]
       [:div.item-tags-readonly
        [task-item/category-badges
         {:item meet
          :category-types [[state/CATEGORY-TYPE-PERSON :people]
                           [state/CATEGORY-TYPE-PLACE :places]
                           [state/CATEGORY-TYPE-PROJECT :projects]
                           [state/CATEGORY-TYPE-GOAL :goals]]
          :toggle-fn state/toggle-shared-filter
          :has-filter-fn state/has-filter-for-type?}]])]))

(defn- report-journal-entry-item [entry]
  (let [is-expanded (= (:expanded-journal-entry @reports-state/*reports-page-state) (:id entry))]
    [:li {:class (str "report-item report-journal-entry" (when is-expanded " expanded"))}
     [:div.item-header
      {:on-click #(swap! reports-state/*reports-page-state assoc :expanded-journal-entry
                         (when-not is-expanded (:id entry)))}
      [:div.item-title
       [relation-link/relation-link-button :journal-entry (:id entry)]
       [:span.item-title-text (:title entry)]]
      (when is-expanded
        [:div.item-toolbar
         [:button.edit-icon {:on-click (fn [e]
                                         (.stopPropagation e)
                                         (state/set-editing-modal :journal-entry entry))}
          "✎"]])]
     (when is-expanded
       [:div.item-details
        (when (seq (:description entry))
          [task-item/clampable-description
           {:text (:description entry)
            :on-click #(state/set-editing-modal :journal-entry entry)}])
        [relation-badges/relation-badges-expanded (:relations entry) "jen" (:id entry)]])]))

(defn- day-section [day-date day-tasks day-meets day-entries]
  [:div.report-day-group {:key day-date}
   [:h4.done-day-header (date/day-formatted day-date)]
   (when (seq day-tasks)
     [:div.report-type-section
      [:h5.report-section-label (t :reports/tasks-section)]
      (into [:ul.items] (map report-task-item day-tasks))])
   (when (seq day-meets)
     [:div.report-type-section
      [:h5.report-section-label (t :reports/meets-section)]
      (into [:ul.items] (map report-meet-item day-meets))])
   (when (seq day-entries)
     [:div.report-type-section
      [:h5.report-section-label (t :reports/journals-section)]
      (into [:ul.items] (map report-journal-entry-item day-entries))])])

(defn- week-section [week-key week-dates tasks-by-day meets-by-day daily-entries-by-day weekly-entries]
  (let [[_ week-num] week-key]
    [:div.report-week-group {:key (str (first week-key) "-" (second week-key))}
     [:h3.report-week-header (i18n/tf :reports/week week-num)]
     (when (seq weekly-entries)
       [:div.report-type-section.report-weekly-journals
        [:h5.report-section-label (t :reports/journals-section)]
        (into [:ul.items] (map report-journal-entry-item weekly-entries))])
     (for [d week-dates]
       ^{:key d}
       [day-section d
        (get tasks-by-day d)
        (get meets-by-day d)
        (get daily-entries-by-day d)])]))

(defn reports-tab []
  (let [{:keys [reports-data]} @state/*app-state
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
      (if (empty? all-week-keys)
        [:p.empty-message (t :reports/no-data)]
        (into [:div.report-weeks]
          (for [wk all-week-keys]
            ^{:key (str (first wk) "-" (second wk))}
            [week-section wk
             (get sorted-dates-by-week wk [])
             tasks-by-day meets-by-day daily-entries-by-day
             (get weekly-entries-by-week wk)])))]]))
