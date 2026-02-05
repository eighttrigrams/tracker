(ns et.tr.ui.components.task-item
  (:require [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.state.tasks-page :as tasks-page]
            [et.tr.i18n :refer [t]]
            ["marked" :refer [marked]]))

(defn markdown [text]
  [:div.markdown-content
   {:dangerouslySetInnerHTML {:__html (marked (or text ""))}}])

(defn task-category-badges [task]
  (let [all-categories (concat
                        (map #(assoc % :type state/CATEGORY-TYPE-PERSON) (:people task))
                        (map #(assoc % :type state/CATEGORY-TYPE-PLACE) (:places task))
                        (map #(assoc % :type state/CATEGORY-TYPE-PROJECT) (:projects task))
                        (map #(assoc % :type state/CATEGORY-TYPE-GOAL) (:goals task)))
        importance (:importance task)
        importance-stars (case importance
                           "important" "★"
                           "critical" "★★"
                           nil)
        has-filters? (state/has-active-filters?)]
    (when (or importance-stars (seq all-categories))
      [:div.task-badges
       (when importance-stars
         [:span.importance-badge {:class importance} importance-stars])
       (doall
        (for [category all-categories]
          ^{:key (str (:type category) "-" (:id category))}
          [:span.tag {:class (:type category)
                      :style (when-not has-filters? {:cursor "pointer"})
                      :on-click (when-not has-filters?
                                  (fn [e]
                                    (.stopPropagation e)
                                    (state/toggle-filter (:type category) (:id category))))}
           (:name category)]))])))

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

(defn category-selector [task category-type entities label]
  (let [selector-id (str (:id task) "-" category-type)
        input-id (str "category-selector-input-" selector-id)]
    (fn [task category-type _ _]
      (let [task-categories (case category-type
                              state/CATEGORY-TYPE-PERSON (:people task)
                              state/CATEGORY-TYPE-PLACE (:places task)
                              state/CATEGORY-TYPE-PROJECT (:projects task)
                              state/CATEGORY-TYPE-GOAL (:goals task))
            task-category-ids (set (map :id task-categories))
            open-selector (:category-selector/open @state/*app-state)
            is-open (= open-selector selector-id)
            search-term (:category-selector/search @state/*app-state)
            available-entities (remove #(contains? task-category-ids (:id %)) entities)
            filtered-entities (if (and is-open (seq search-term))
                                (filter #(tasks-page/prefix-matches? (:name %) search-term) available-entities)
                                available-entities)]
        [:div.tag-selector
         [:div.category-selector-dropdown
          [:button.category-selector-trigger
           {:class (str category-type (when is-open " open"))
            :on-click (fn [e]
                        (.stopPropagation e)
                        (if is-open
                          (do
                            (state/close-category-selector)
                            (state/focus-tasks-search))
                          (do
                            (state/open-category-selector selector-id)
                            (js/setTimeout #(when-let [el (.getElementById js/document input-id)]
                                              (.focus el)) 0))))}
           (str "+ " label)]
          (when is-open
            [:div.category-selector-panel
             {:on-click #(.stopPropagation %)}
             [:input.category-selector-search
              {:id input-id
               :type "text"
               :placeholder (t :category/search)
               :value search-term
               :auto-focus true
               :on-change #(state/set-category-selector-search (-> % .-target .-value))
               :on-key-down (fn [e]
                              (case (.-key e)
                                "Escape" (do
                                           (state/close-category-selector)
                                           (state/focus-tasks-search))
                                "Enter" (when (= 1 (count filtered-entities))
                                          (state/categorize-task (:id task) category-type (:id (first filtered-entities)))
                                          (state/close-category-selector)
                                          (state/focus-tasks-search))
                                nil))}]
             [:div.category-selector-items
              (if (seq filtered-entities)
                (doall
                 (for [entity filtered-entities]
                   ^{:key (:id entity)}
                   [:button.category-selector-item
                    {:class category-type
                     :on-click (fn [e]
                                 (.stopPropagation e)
                                 (state/categorize-task (:id task) category-type (:id entity))
                                 (state/close-category-selector)
                                 (state/focus-tasks-search))}
                    (:name entity)]))
                [:div.category-selector-empty (t :category/no-results)])]])]
         (doall
          (for [category task-categories]
            ^{:key (str category-type "-" (:id category))}
            [:span.tag
             {:class category-type}
             (:name category)
             [:button.remove-tag
              {:on-click #(state/uncategorize-task (:id task) category-type (:id category))}
              "x"]]))]))))
