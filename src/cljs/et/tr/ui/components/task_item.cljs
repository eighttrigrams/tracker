(ns et.tr.ui.components.task-item
  (:require [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.components.category-selector :as category-selector]
            [et.tr.ui.components.relation-badges :as relation-badges]
            [et.tr.filters :as filters]
            [et.tr.i18n :refer [t]]
            ["marked" :refer [marked]]))

(defn markdown [text]
  [:div.markdown-content
   {:dangerouslySetInnerHTML {:__html (marked (or text ""))}}])

(defn category-badges [{:keys [item category-types toggle-fn has-filter-fn]}]
  (let [all-categories (mapcat (fn [[type k]] (map #(assoc % :type type) (get item k))) category-types)]
    (when (seq all-categories)
      (into [:div.task-badges]
            (for [category all-categories]
              (let [type-has-filter? (has-filter-fn (:type category))
                    clickable? (not type-has-filter?)]
                ^{:key (str (:type category) "-" (:id category))}
                [:span.tag {:class (:type category)
                            :style (when clickable? {:cursor "pointer"})
                            :on-click (when clickable?
                                        (fn [e]
                                          (.stopPropagation e)
                                          (toggle-fn (:type category) (:id category))))}
                 (filters/badge-label category)]))))))

(defn task-category-badges [task]
  (let [importance (:importance task)
        importance-stars (case importance
                           "important" "★"
                           "critical" "★★"
                           nil)
        on-tasks-page? (= :tasks (:active-tab @state/*app-state))
        all-types [[state/CATEGORY-TYPE-PERSON :people]
                   [state/CATEGORY-TYPE-PLACE :places]
                   [state/CATEGORY-TYPE-PROJECT :projects]
                   [state/CATEGORY-TYPE-GOAL :goals]]
        has-categories? (some #(seq (get task (second %))) all-types)
        has-relations? (seq (:relations task))]
    (when (or importance-stars has-categories? has-relations?)
      [:div.task-badges
       (when importance-stars
         [:span.importance-badge {:class importance} importance-stars])
       (when has-categories?
         (into [:<>]
               (for [category (mapcat (fn [[type k]] (map #(assoc % :type type) (get task k))) all-types)]
                 (let [type-has-filter? (state/has-filter-for-type? (:type category))
                       clickable? (not type-has-filter?)]
                   ^{:key (str (:type category) "-" (:id category))}
                   [:span.tag {:class (:type category)
                               :style (when clickable? {:cursor "pointer"})
                               :on-click (when clickable?
                                           (fn [e]
                                             (.stopPropagation e)
                                             (if on-tasks-page?
                                               (state/toggle-filter (:type category) (:id category))
                                               (state/toggle-shared-filter (:type category) (:id category)))))}
                    (filters/badge-label category)]))))
       (when has-relations?
         [relation-badges/relation-badges-collapsed (:relations task) "tsk" (:id task)])])))

(defn task-scope-selector [task]
  (let [scope (or (:scope task) "both")]
    [:div.task-scope-selector.toggle-group.compact
     (for [s ["private" "both" "work"]]
       ^{:key s}
       [:button.toggle-option
        {:class (when (= scope s) "active")
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-task-scope (:id task) s))}
        s])]))

(defn- task-level-selector
  [{:keys [task attr-key default-value levels labels css-class set-fn]}]
  (let [current-value (or (get task attr-key) default-value)]
    [(keyword (str "div." css-class ".toggle-group.compact"))
     (for [level levels]
       ^{:key level}
       [:button.toggle-option
        {:class (str level (when (= current-value level) " active"))
         :on-click (fn [e]
                     (.stopPropagation e)
                     (set-fn (:id task) level))}
        (labels level)])]))

(defn task-importance-selector [task]
  [task-level-selector
   {:task task
    :attr-key :importance
    :default-value "normal"
    :levels ["normal" "important" "critical"]
    :labels {"normal" "○" "important" "★" "critical" "★★"}
    :css-class "task-importance-selector"
    :set-fn state/set-task-importance}])

(defn task-urgency-selector [task]
  [task-level-selector
   {:task task
    :attr-key :urgency
    :default-value "default"
    :levels ["default" "urgent" "superurgent"]
    :labels {"default" "—" "urgent" "🚨" "superurgent" "🚨🚨"}
    :css-class "task-urgency-selector"
    :set-fn state/set-task-urgency}])

(defn task-attribute-selectors [task & {:keys [show-importance? show-urgency?]
                                        :or {show-importance? true show-urgency? true}}]
  [:<>
   [task-scope-selector task]
   (when show-importance?
     [task-importance-selector task])
   (when show-urgency?
     [task-urgency-selector task])])

(defn task-combined-action-button [task]
  [:div.combined-button-wrapper
   [:button.combined-main-btn
    {:class (if (state/task-done? task) "undone" "done")
     :on-click #(state/set-task-done (:id task) (not (state/task-done? task)))}
    (if (state/task-done? task)
      (t :task/set-undone)
      (t :task/mark-done))]
   [:button.combined-dropdown-btn
    {:class (if (state/task-done? task) "undone" "done")
     :on-click #(state/set-task-dropdown-open (:id task))}
    "▼"]
   (when (= (:id task) (:task-dropdown-open @state/*app-state))
     [:div.task-dropdown-menu
      [:button.dropdown-item
       {:on-click #(do
                     (state/set-task-dropdown-open nil)
                     (state/set-confirm-delete-task task))}
       (t :task/delete)]])])

(defn time-picker [task & {:keys [show-clear?] :or {show-clear? false}}]
  [:span.time-picker-wrapper
   {:on-click #(.stopPropagation %)}
   [:input.time-picker-input
    {:type "time"
     :value (or (:due_time task) "")
     :on-change (fn [e]
                  (let [v (.. e -target -value)]
                    (state/set-task-due-time (:id task) (when (seq v) v))))}]
   [:button.clock-icon {:on-click (fn [e]
                                    (.stopPropagation e)
                                    (-> e .-currentTarget .-parentElement (.querySelector "input") .showPicker))}
    "🕐"]
   (when (and show-clear? (seq (:due_time task)))
     [:button.clear-time {:on-click (fn [e]
                                      (.stopPropagation e)
                                      (state/set-task-due-time (:id task) nil))}
      "✕"])])

(defn item-edit-form
  [{:keys [title-atom description-atom tags-atom
           badge-title-atom badge-title-placeholder
           title-placeholder description-placeholder tags-placeholder
           on-save on-cancel on-delete]}]
  [:div.item-edit-form
   [:input {:type "text"
            :value @title-atom
            :on-change #(reset! title-atom (-> % .-target .-value))
            :placeholder title-placeholder}]
   [:textarea {:value @description-atom
               :on-change #(reset! description-atom (-> % .-target .-value))
               :placeholder description-placeholder
               :rows 3}]
   (when tags-atom
     [:input {:type "text"
              :value @tags-atom
              :on-change #(reset! tags-atom (-> % .-target .-value))
              :placeholder tags-placeholder}])
   (when badge-title-atom
     [:input {:type "text"
              :value @badge-title-atom
              :on-change #(reset! badge-title-atom (-> % .-target .-value))
              :placeholder badge-title-placeholder}])
   [:div.edit-buttons
    [:button {:on-click on-save} (t :task/save)]
    [:button.cancel {:on-click on-cancel} (t :task/cancel)]
    (when on-delete
      [:button.delete-btn {:on-click on-delete} (t :category/delete)])]])

(defn task-edit-form [task]
  (let [title (r/atom (:title task))
        description (r/atom (or (:description task) ""))
        tags (r/atom (or (:tags task) ""))]
    (fn []
      [item-edit-form
       {:title-atom title
        :description-atom description
        :tags-atom tags
        :title-placeholder (t :task/title-placeholder)
        :description-placeholder (t :task/description-placeholder)
        :tags-placeholder (t :task/tags-placeholder)
        :on-save (fn []
                   (state/update-task (:id task) @title @description @tags
                                      #(state/clear-editing)))
        :on-cancel #(state/clear-editing)}])))

(defn task-categories-readonly [task]
  [:div.item-tags-readonly
   [task-category-badges task]])

(defn category-selector [_task _category-type _entities _label]
  (fn [task* category-type* entities* label*]
    (let [task-categories (case category-type*
                            state/CATEGORY-TYPE-PERSON (:people task*)
                            state/CATEGORY-TYPE-PLACE (:places task*)
                            state/CATEGORY-TYPE-PROJECT (:projects task*)
                            state/CATEGORY-TYPE-GOAL (:goals task*))]
      [category-selector/category-selector
       {:entity task*
        :entity-id-key :id
        :category-type category-type*
        :entities entities*
        :label label*
        :current-categories task-categories
        :on-categorize #(state/categorize-task (:id task*) category-type* %)
        :on-uncategorize #(state/uncategorize-task (:id task*) category-type* %)
        :on-close-focus-fn state/focus-tasks-search
        :open-selector-state (:category-selector/open @state/*app-state)
        :search-state (:category-selector/search @state/*app-state)
        :open-selector-fn state/open-category-selector
        :close-selector-fn state/close-category-selector
        :set-search-fn state/set-category-selector-search}])))
