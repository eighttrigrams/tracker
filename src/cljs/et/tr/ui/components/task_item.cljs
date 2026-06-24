(ns et.tr.ui.components.task-item
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.tr.ui.state :as state]
            [et.tr.ui.date :as date]
            [et.tr.ui.components.category-selector :as category-selector]
            [et.tr.ui.components.relation-badges :as relation-badges]
            [et.tr.filters :as filters]
            [et.tr.i18n :refer [t]]
            ["marked" :refer [marked]]))

(defn markdown [text]
  [:div.markdown-content
   {:dangerouslySetInnerHTML (r/unsafe-html (marked (or text "")))}])

(defn html [text]
  [:div.markdown-content.html-content
   {:dangerouslySetInnerHTML (r/unsafe-html (or text ""))}])

(defn- body-renderer [content-type]
  (if (= content-type "html") html markdown))

(defn- markdown-blocks [text]
  (str/split (or text "") #"\r?\n\r?\n+"))

(defn clampable-description [_]
  (let [expanded? (r/atom false)]
    (fn [{:keys [text on-click content-type]}]
      (let [render (body-renderer content-type)
            blocks (markdown-blocks text)
            html? (= content-type "html")
            needs-clamp? (and (not html?) (> (count blocks) 10))
            visible (if (and needs-clamp? (not @expanded?))
                      (str/join "\n\n" (take 10 blocks))
                      text)]
        [:<>
         [:div.item-description
          {:on-click (fn [e]
                       (when (.. js/window getSelection -isCollapsed)
                         (.stopPropagation e)
                         (when on-click (on-click))))}
          [render visible]]
         (when (and needs-clamp? (not @expanded?))
           [:span.see-more
            {:on-click (fn [e]
                         (.stopPropagation e)
                         (reset! expanded? true))}
            "See more"])]))))

(defn inline-title-edit [{:keys [title on-change on-commit on-cancel]}]
  [:input.inline-title-edit
   {:type "text"
    :auto-complete "off"
    :auto-focus true
    :value title
    :on-click #(.stopPropagation %)
    :on-change #(on-change (.. % -target -value))
    :on-key-down (fn [e]
                   (case (.-key e)
                     "Enter" (do (.stopPropagation e) (on-commit))
                     "Escape" (do (.stopPropagation e) (on-cancel))
                     nil))
    :on-blur (fn [_] (on-commit))}])

(defn category-badges [{:keys [item category-types toggle-fn has-filter-fn force-show?]}]
  (let [all-categories (mapcat (fn [[type k]] (map #(assoc % :type type) (get item k))) category-types)]
    (when (and (or force-show? (state/show-collapsed-categories?)) (seq all-categories))
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
        all-types [[state/CATEGORY-TYPE-PERSON :people]
                   [state/CATEGORY-TYPE-PLACE :places]
                   [state/CATEGORY-TYPE-PROJECT :projects]
                   [state/CATEGORY-TYPE-GOAL :goals]]
        has-categories? (some #(seq (get task (second %))) all-types)
        show-categories? (and (state/show-collapsed-categories?) has-categories?)
        has-relations? (seq (:relations task))]
    (when (or importance-stars show-categories? has-relations?)
      [:div.task-badges
       (when importance-stars
         [:span.importance-badge {:class importance} importance-stars])
       (when show-categories?
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
                                             (state/toggle-shared-filter (:type category) (:id category))))}
                    (filters/badge-label category)]))))
       (when has-relations?
         [relation-badges/relation-badges-collapsed (:relations task) "tsk" (:id task)])])))

(defn done-button-spec [task extra-dropdown-items]
  (if (= "active" (:reminder task))
    {:label (t :task/acknowledge-reminder)
     :variant :acknowledge
     :on-click #(state/acknowledge-task-reminder (:id task))}
    (let [done? (state/task-done? task)]
      {:label (if done? (t :task/set-undone) (t :task/mark-done))
       :variant (if done? :undone :done)
       :on-click #(if done?
                    (state/set-confirm-undone-task task)
                    (state/set-task-done (:id task) true))
       :dropdown {:open? (= (:id task) (:task-dropdown-open @state/*app-state))
                  :on-toggle #(state/set-task-dropdown-open (:id task))
                  :items (concat
                           (or extra-dropdown-items [])
                           [(if done?
                              {:label (t :task/change-done-date)
                               :on-click #(do
                                            (state/set-task-dropdown-open nil)
                                            (state/open-done-date-modal task))}
                              {:label (if (:reminder_date task)
                                        (t :task/change-reminder)
                                        (t :task/set-reminder))
                               :class "set-reminder"
                               :title (when (:reminder_date task)
                                        (t :task/current-reminder {:date (date/format-date-localized (:reminder_date task))}))
                               :on-click #(do
                                            (state/set-task-dropdown-open nil)
                                            (state/open-reminder-modal task))})
                            {:label (t :task/delete)
                             :on-click #(do
                                          (state/set-task-dropdown-open nil)
                                          (state/set-confirm-delete-task task))}])}})))

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

(defn meet-category-selector [_meet _category-type _entities _label]
  (fn [meet* category-type* entities* label*]
    (let [current (case category-type*
                    state/CATEGORY-TYPE-PERSON (:people meet*)
                    state/CATEGORY-TYPE-PLACE (:places meet*)
                    state/CATEGORY-TYPE-PROJECT (:projects meet*)
                    state/CATEGORY-TYPE-GOAL (:goals meet*)
                    [])]
      [category-selector/category-selector
       {:entity meet*
        :entity-id-key :id
        :category-type category-type*
        :entities entities*
        :label label*
        :current-categories current
        :on-categorize #(state/categorize-meet (:id meet*) category-type* %)
        :on-uncategorize #(state/uncategorize-meet (:id meet*) category-type* %)
        :on-close-focus-fn nil
        :open-selector-state (:category-selector/open @state/*app-state)
        :search-state (:category-selector/search @state/*app-state)
        :open-selector-fn state/open-category-selector
        :close-selector-fn state/close-category-selector
        :set-search-fn state/set-category-selector-search}])))

(defn journal-entry-category-selector [_entry _category-type _entities _label]
  (fn [entry* category-type* entities* label*]
    (let [current (case category-type*
                    state/CATEGORY-TYPE-PERSON (:people entry*)
                    state/CATEGORY-TYPE-PLACE (:places entry*)
                    state/CATEGORY-TYPE-PROJECT (:projects entry*)
                    state/CATEGORY-TYPE-GOAL (:goals entry*)
                    [])]
      [category-selector/category-selector
       {:entity entry*
        :entity-id-key :id
        :category-type category-type*
        :entities entities*
        :label label*
        :current-categories current
        :on-categorize #(state/categorize-journal-entry (:id entry*) category-type* %)
        :on-uncategorize #(state/uncategorize-journal-entry (:id entry*) category-type* %)
        :on-close-focus-fn nil
        :open-selector-state (:category-selector/open @state/*app-state)
        :search-state (:category-selector/search @state/*app-state)
        :open-selector-fn state/open-category-selector
        :close-selector-fn state/close-category-selector
        :set-search-fn state/set-category-selector-search}])))
