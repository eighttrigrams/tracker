(ns et.tr.ui.views.issues
  (:require [reagent.core]
            [et.tr.ui.state :as state]
            [et.tr.ui.state.issues :as issues-state]
            [et.tr.ui.components.item-card :as item-card]
            [et.tr.ui.components.drag-drop :as drag-drop]
            [et.tr.ui.components.filter-section :as filter-section]
            [et.tr.ui.components.category-selector :as category-selector]
            [et.tr.i18n :refer [t]]))

(def ^:private issues-category-shortcut-keys
  {"Digit1" :people
   "Digit2" :places
   "Digit3" :projects
   "Digit4" :goals})

(def issues-category-shortcut-numbers
  (into {} (map (fn [[k v]] [v (subs k 5)]) issues-category-shortcut-keys)))

(defn get-issues-category-shortcut-keys []
  issues-category-shortcut-keys)

(defn- issue-category-selector [issue category-type entities label]
  (let [current-categories (case category-type
                             state/CATEGORY-TYPE-PERSON (:people issue)
                             state/CATEGORY-TYPE-PLACE (:places issue)
                             state/CATEGORY-TYPE-PROJECT (:projects issue)
                             state/CATEGORY-TYPE-GOAL (:goals issue)
                             [])]
    [category-selector/category-selector
     {:entity issue
      :entity-id-key :id
      :category-type category-type
      :entities entities
      :label label
      :current-categories current-categories
      :on-categorize #(state/categorize-issue (:id issue) category-type %)
      :on-uncategorize #(state/uncategorize-issue (:id issue) category-type %)
      :on-close-focus-fn nil
      :open-selector-state (:category-selector/open @state/*app-state)
      :search-state (:category-selector/search @state/*app-state)
      :open-selector-fn state/open-category-selector
      :close-selector-fn state/close-category-selector
      :set-search-fn state/set-category-selector-search}]))

(defn- issue-task-badge [issue-id task expanded?]
  [:span.tag.relation
   {:key (str "tsk-" (:id task))
    :class (when (= 1 (:done task)) "task-done")
    :on-click (fn [e]
                (.stopPropagation e)
                (state/open-relation-in-modal "tsk" (:id task)))}
   (if (= 1 (:done task)) "☑ " "☐ ")
   (:title task)
   (when expanded?
     [:button.remove-tag
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (state/delete-relation "iss" issue-id "tsk" (:id task)))}
      "×"])])

(defn- issue-tasks [issue expanded?]
  (when (seq (:tasks issue))
    (into [:div.issue-tasks
           (if expanded? {:class "relation-badges-expanded"} {:class "relation-badges-collapsed"})]
          (for [task (:tasks issue)]
            ^{:key (:id task)}
            [issue-task-badge (:id issue) task expanded?]))))

(defn- issue-item [issue expanded-id drag-enabled? drag-issue drag-over-issue]
  (let [is-expanded (= expanded-id (:id issue))
        is-dragging (= drag-issue (:id issue))
        is-drag-over (= drag-over-issue (:id issue))]
    [item-card/item-card
     {:item issue
      :expanded? is-expanded
      :on-toggle #(state/set-expanded-issue (when-not is-expanded (:id issue)))
      :container {:tag :li
                  :class (str (when is-dragging "dragging")
                              (when is-drag-over " drag-over")
                              (when-not drag-enabled? " drag-disabled"))
                  :attrs {:draggable drag-enabled?
                          :on-drag-start (drag-drop/make-drag-start-handler issue state/set-drag-issue drag-enabled?)
                          :on-drag-end (fn [_] (state/clear-issue-drag-state))
                          :on-drag-over (drag-drop/make-drag-over-handler issue state/set-drag-over-issue drag-enabled?)
                          :on-drag-leave (drag-drop/make-drag-leave-handler drag-over-issue issue #(state/set-drag-over-issue nil))
                          :on-drop (drag-drop/make-drop-handler drag-issue issue state/reorder-issue drag-enabled?)}}
      :relation-link [:issue (:id issue)]
      :inline-edit (item-card/make-inline-edit
                     {:edit-id-path :issues-page/inline-edit-issue
                      :title-path :issues-page/inline-edit-title
                      :update-fn state/update-issue})
      :badges {:importance? true}
      :title-expanded-click (fn [i] (state/open-edit-modal :issue i))
      :description {:edit-type :issue
                    :on-edit state/edit-issue-description}
      :categories {:selector-fn issue-category-selector :relations-prefix "iss"}
      :expanded-suffix [issue-tasks issue true]
      :readonly-extra [issue-tasks issue false]
      :footer {:left [{:type :scope :value (:scope issue)
                       :on-set #(state/set-issue-scope (:id issue) %)}
                      {:type :importance :value (:importance issue)
                       :on-set #(state/set-issue-importance (:id issue) %)}]
               :right [{:type :delete :on-click #(state/set-confirm-delete-issue issue)}]}}]))

(defn- sort-mode-toggle []
  (let [sort-mode (:sort-mode @issues-state/*issues-page-state)]
    [:div.sort-toggle.toggle-group
     [:button {:class (when (= sort-mode :recent) "active")
               :on-click #(when (not= sort-mode :recent) (state/set-issue-sort-mode :recent))}
      (t :resources/sort-recent)]
     [:button {:class (when (= sort-mode :manual) "active")
               :on-click #(when (not= sort-mode :manual) (state/set-issue-sort-mode :manual))}
      (t :resources/sort-manual)]
     [:button {:class (when (= sort-mode :added) "active")
               :on-click #(when (not= sort-mode :added) (state/set-issue-sort-mode :added))}
      (t :resources/sort-added)]]))

(defn- importance-filter-toggle []
  (let [importance-filter (:importance-filter @issues-state/*issues-page-state)]
    [:div.importance-filter-toggle.toggle-group
     [:button {:class (when (nil? importance-filter) "active")
               :on-click #(state/set-issue-importance-filter nil)
               :title (t :importance/filter-off)}
      "○"]
     [:button {:class (str "important" (when (= importance-filter :important) " active"))
               :on-click #(state/set-issue-importance-filter :important)
               :title (t :importance/filter-important)}
      "★"]
     [:button {:class (str "critical" (when (= importance-filter :critical) " active"))
               :on-click #(state/set-issue-importance-filter :critical)
               :title (t :importance/filter-critical)}
      "★★"]]))

(defn- search-add-form []
  (let [input-value (:filter-search @issues-state/*issues-page-state)]
    [:div.combined-search-add-form
     [:input#issues-filter-search
      {:type "text"
       :auto-complete "off"
       :placeholder (t :issues/search-or-add)
       :value input-value
       :on-change #(state/set-issue-filter-search (-> % .-target .-value))
       :on-key-down (fn [e]
                      (cond
                        (and (= (.-key e) "Enter") (seq input-value))
                        (do
                          (.preventDefault e)
                          (state/add-issue input-value #(state/set-issue-filter-search "")))

                        (= (.-key e) "Escape")
                        (state/set-issue-filter-search "")))}]
     [:button {:on-click #(when (seq input-value)
                            (state/add-issue input-value (fn [] (state/set-issue-filter-search ""))))}
      (t :tasks/add-button)]
     (when (seq input-value)
       [:button.clear-search {:on-click #(state/set-issue-filter-search "")} "x"])]))

(defn- issues-filter-section [{:keys [title filter-key items selected-ids toggle-fn clear-fn collapsed?]}]
  [filter-section/category-filter-section {:title title
                                           :shortcut-number (issues-category-shortcut-numbers filter-key)
                                           :filter-key filter-key
                                           :items items
                                           :marked-ids selected-ids
                                           :toggle-fn toggle-fn
                                           :clear-fn clear-fn
                                           :collapsed? collapsed?
                                           :toggle-collapsed-fn state/toggle-issues-filter-collapsed
                                           :set-search-fn state/set-issues-category-search
                                           :search-state-path [:issues-page/category-search filter-key]
                                           :section-class (name filter-key)
                                           :item-active-class "active"
                                           :label-class nil
                                           :page-prefix "issues"}])

(def ^:private issues-sidebar-filter-configs
  [{:filter-key :people :title-key :category/people :items-key :people
    :filter-state-key :shared/filter-people :category-type state/CATEGORY-TYPE-PERSON}
   {:filter-key :places :title-key :category/places :items-key :places
    :filter-state-key :shared/filter-places :category-type state/CATEGORY-TYPE-PLACE}
   {:filter-key :projects :title-key :category/projects :items-key :projects
    :filter-state-key :shared/filter-projects :category-type state/CATEGORY-TYPE-PROJECT}
   {:filter-key :goals :title-key :category/goals :items-key :goals
    :filter-state-key :shared/filter-goals :category-type state/CATEGORY-TYPE-GOAL}])

(defn sidebar-filters []
  (let [app-state @state/*app-state
        collapsed-filters (:issues-page/collapsed-filters app-state)]
    (into [:div.sidebar [filter-section/category-badge-toggle]]
          (for [{:keys [filter-key title-key items-key filter-state-key category-type]} issues-sidebar-filter-configs]
            [issues-filter-section {:title (t title-key)
                                    :filter-key filter-key
                                    :items (get app-state items-key)
                                    :selected-ids (get app-state filter-state-key)
                                    :toggle-fn #(state/toggle-shared-filter category-type %)
                                    :clear-fn #(state/clear-shared-filter category-type)
                                    :collapsed? (contains? collapsed-filters filter-key)}]))))

(defn issues-tab []
  (let [{:keys [issues drag-issue drag-over-issue]} @state/*app-state
        {:keys [expanded-issue sort-mode]} @issues-state/*issues-page-state
        manual-mode? (= sort-mode :manual)
        any-open? (some? expanded-issue)
        drag-enabled? (and manual-mode? (not any-open?))]
    [:div.main-layout
     [sidebar-filters]
     [:div.main-content.issues-page
      [:div.tasks-header
       [importance-filter-toggle]
       [sort-mode-toggle]]
      [search-add-form]
      (if (empty? issues)
        [:p.empty-message (t :issues/no-issues)]
        [:<>
         [:ul.items
          (for [issue issues]
            ^{:key (:id issue)}
            [issue-item issue expanded-issue drag-enabled? drag-issue drag-over-issue])]
         (when (:has-more? @issues-state/*issues-page-state)
           [:div.load-more
            [:button.load-more-btn {:on-click #(state/load-more-issues)}
             (t :resources/see-more)]])])]]))
