(ns et.tr.ui.views.issues
  (:require [reagent.core]
            [et.tr.ui.state :as state]
            [et.tr.ui.state.issues :as issues-state]
            [et.tr.ui.components.item-card :as item-card]
            [et.tr.ui.components.drag-drop :as drag-drop]
            [et.tr.ui.components.filter-section :as filter-section]
            [et.tr.ui.components.category-selector :as category-selector]
            [et.tr.ui.views.tasks :as tasks-view]
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

(defn- issue-resolved-button-spec [issue]
  ;; Split-button mirroring the task done-button: primary toggles resolved,
  ;; the dropdown carries Delete (in red). Resolving is disabled while the
  ;; issue still has undone tasks (business rule 1); reopening goes through a
  ;; confirm modal, matching the task set-undone flow.
  (let [id (:id issue)
        resolved? (state/issue-resolved? issue)
        blocked? (and (not resolved?) (state/issue-has-undone-tasks? issue))]
    {:label (if resolved? (t :issues/set-unresolved) (t :issues/set-resolved))
     :variant (if resolved? :undone :done)
     :disabled blocked?
     :title (when blocked? (t :issues/resolve-blocked))
     :on-click (cond
                 resolved? #(state/set-confirm-unresolve-issue issue)
                 blocked? nil
                 :else #(state/set-issue-resolved id true))
     :dropdown {:open? (= id (:issue-dropdown-open @issues-state/*issues-page-state))
                :on-toggle #(state/set-issue-dropdown-open id)
                :items [{:label (t :task/delete)
                         :on-click #(do
                                      (state/set-issue-dropdown-open nil)
                                      (state/set-confirm-delete-issue issue))}]}}))

(defn- issue-create-task-button [issue]
  ;; Mirrors the meeting-series create-meet pattern: the collapsed parent card
  ;; offers a button that opens a modal (a title input here, rather than the
  ;; series' date picker); confirming materialises a task belonging to the issue.
  [:button.create-next-meeting-btn
   {:on-click (fn [e]
                (.stopPropagation e)
                (state/open-create-task-modal issue))}
   (t :tasks/create-task)])

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
      ;; Mirrors the meeting-series down-arrow (series-filter-btn): a top-right
      ;; button that jumps straight to this issue's focused task listing.
      :header-extra [:button.issue-filter-btn
                     {:on-click (fn [e]
                                  (.stopPropagation e)
                                  (state/focus-issue (:id issue)))
                      :title (t :issues/show-tasks)}
                     "⏚"]
      :badges {:importance? true}
      :description {:edit-type :issue
                    :on-edit state/edit-issue-description}
      :categories {:selector-fn issue-category-selector :relations-prefix "iss"}
      :readonly-extra (when-not (state/issue-resolved? issue)
                        [issue-create-task-button issue])
      :footer {:scope {:value (:scope issue)
                       :on-set #(state/set-issue-scope (:id issue) %)}
               :importance {:value (:importance issue)
                            :on-set #(state/set-issue-importance (:id issue) %)}
               :urgency {:value (:urgency issue)
                         :on-set #(state/set-issue-urgency (:id issue) %)}
               :main-actions (issue-resolved-button-spec issue)}}]))

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
      (t :resources/sort-added)]
     [:button {:class (when (= sort-mode :resolved) "active")
               :on-click #(when (not= sort-mode :resolved) (state/set-issue-sort-mode :resolved))}
      (t :issues/sort-resolved)]]))

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

(defn- issue-filter-bar []
  ;; Mirrors the recurring-tasks / journal filter bar shown when the page is
  ;; focused on a single parent. Clicking the label opens the issue for editing.
  (let [f (state/issue-filter)
        focused (state/focused-issue)]
    [:div.series-filter-bar
     [:span.series-filter-label
      {:on-click #(when focused (state/open-edit-modal :issue focused))
       :style {:cursor "pointer"}}
      (:title f)]
     [:button.clear-search {:on-click #(state/clear-issue-filter)} "x"]]))

(defn- focused-issue-tasks []
  ;; The task listing for the focused issue — this is where an issue's belonging
  ;; tasks are surfaced (the issue card itself no longer shows them, matching the
  ;; unidirectional journal↔entry model). Tasks are the genuine Tasks-page cards
  ;; (tasks-view/task-item-content), driven off :tasks which focus-issue loads
  ;; scoped to this issue.
  (let [issue-id (:id (state/issue-filter))
        expanded-task (:tasks-page/expanded-task @state/*app-state)
        tasks (filter #(= issue-id (:issue_id %)) (state/filtered-tasks))
        not-done (remove #(= 1 (:done %)) tasks)
        done (filter #(= 1 (:done %)) tasks)
        render-task (fn [task]
                      ^{:key (:id task)}
                      ;; Hide the per-task ◈ belongs-to-issue indicator: every task
                      ;; in this listing already belongs to the focused issue.
                      [tasks-view/task-item-content task (= expanded-task (:id task)) false {:tag :li}
                       {:hide-issue-icon? true}])]
    (if (seq tasks)
      [:<>
       (into [:ul.items] (map render-task not-done))
       (when (seq done)
         [:<>
          [:hr.done-divider]
          [:h4.done-heading (t :tasks/completed)]
          (into [:ul.items] (map render-task done))])]
      [:p.empty-message (t :issues/no-tasks)])))

(defn issues-tab []
  (let [{:keys [issues drag-issue drag-over-issue]} @state/*app-state
        {:keys [expanded-issue sort-mode]} @issues-state/*issues-page-state
        issue-filter (state/issue-filter)
        manual-mode? (= sort-mode :manual)
        any-open? (some? expanded-issue)
        drag-enabled? (and manual-mode? (not any-open?))]
    [:div.main-layout
     [sidebar-filters]
     [:div.main-content.issues-page
      [:div.tasks-header
       (when-not issue-filter [importance-filter-toggle])
       (when-not issue-filter [sort-mode-toggle])]
      (cond
        issue-filter
        [:<>
         [issue-filter-bar]
         [focused-issue-tasks]]

        (empty? issues)
        [:<>
         [search-add-form]
         [:p.empty-message (t :issues/no-issues)]]

        :else
        [:<>
         [search-add-form]
         [:ul.items
          (for [issue issues]
            ^{:key (:id issue)}
            [issue-item issue expanded-issue drag-enabled? drag-issue drag-over-issue])]
         (when (:has-more? @issues-state/*issues-page-state)
           [:div.load-more
            [:button.load-more-btn {:on-click #(state/load-more-issues)}
             (t :resources/see-more)]])])]]))
