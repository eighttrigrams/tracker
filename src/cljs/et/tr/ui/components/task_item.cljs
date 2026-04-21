(ns et.tr.ui.components.task-item
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.tr.ui.state :as state]
            [et.tr.ui.components.category-selector :as category-selector]
            [et.tr.ui.components.relation-badges :as relation-badges]
            [et.tr.filters :as filters]
            [et.tr.i18n :refer [t]]
            ["marked" :refer [marked]]))

(defn markdown [text]
  [:div.markdown-content
   {:dangerouslySetInnerHTML {:__html (marked (or text ""))}}])

(defn- markdown-blocks [text]
  (str/split (or text "") #"\r?\n\r?\n+"))

(defn clampable-description [_]
  (let [expanded? (r/atom false)]
    (fn [{:keys [text on-click]}]
      (let [blocks (markdown-blocks text)
            needs-clamp? (> (count blocks) 10)
            visible (if (and needs-clamp? (not @expanded?))
                      (str/join "\n\n" (take 10 blocks))
                      text)]
        [:<>
         [:div.item-description
          {:on-click (fn [e]
                       (when (.. js/window getSelection -isCollapsed)
                         (.stopPropagation e)
                         (when on-click (on-click))))}
          [markdown visible]]
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
        active-tab (:active-tab @state/*app-state)
        on-tasks-page? (= :tasks active-tab)
        on-today-page? (= :today active-tab)
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
                       clickable? (and (not on-today-page?) (not type-has-filter?))]
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
   (when (and show-urgency? (not (:due_date task)))
     [task-urgency-selector task])])

(defn task-combined-action-button [task & {:keys [extra-dropdown-items]}]
  (if (= "active" (:reminder task))
    [:div.combined-button-wrapper
     [:button.combined-main-btn.acknowledge-reminder
      {:on-click #(state/acknowledge-task-reminder (:id task))}
      (t :task/acknowledge-reminder)]]
    [:div.combined-button-wrapper
     [:button.combined-main-btn
      {:class (if (state/task-done? task) "undone" "done")
       :on-click #(if (state/task-done? task)
                    (state/set-confirm-undone-task task)
                    (state/set-task-done (:id task) true))}
      (if (state/task-done? task)
        (t :task/set-undone)
        (t :task/mark-done))]
     [:button.combined-dropdown-btn
      {:class (if (state/task-done? task) "undone" "done")
       :on-click #(state/set-task-dropdown-open (:id task))}
      "▼"]
     (when (= (:id task) (:task-dropdown-open @state/*app-state))
       [:div.task-dropdown-menu
        (when extra-dropdown-items
          extra-dropdown-items)
        [:button.dropdown-item.set-reminder
         {:on-click #(do
                       (state/set-task-dropdown-open nil)
                       (state/open-reminder-modal task))}
         (t :task/set-reminder)]
        [:button.dropdown-item
         {:on-click #(do
                       (state/set-task-dropdown-open nil)
                       (state/set-confirm-delete-task task))}
         (t :task/delete)]])]))

(defn- parse-time [time-str]
  (when (seq time-str)
    (let [[h m] (map js/parseInt (.split time-str ":"))]
      {:hour h :minute m})))

(defn- format-time [hour minute]
  (str (.padStart (str hour) 2 "0") ":" (.padStart (str minute) 2 "0")))

(defn- current-time-default []
  (let [now (js/Date.)
        m (.getMinutes now)
        rounded (* 5 (js/Math.round (/ m 5)))]
    (format-time (.getHours now) (mod rounded 60))))

(defn generic-time-picker [_entity & _opts]
  (let [open? (r/atom false)
        close! (fn [] (reset! open? false))]
    (fn [entity & {:keys [show-clear? time-key on-change] :or {show-clear? false time-key :due_time on-change nil}}]
      (let [on-change (or on-change #(state/set-task-due-time (:id entity) %))
            time-val (get entity time-key)
            parsed (parse-time time-val)
            current-hour (:hour parsed)
            current-minute (:minute parsed)]
        [:span.time-picker-wrapper
         {:on-click #(.stopPropagation %)}
         [:button.clock-icon {:on-click (fn [e]
                                          (.stopPropagation e)
                                          (when (and (not @open?) (not (seq time-val)))
                                            (on-change (current-time-default)))
                                          (swap! open? not))}
          "🕐"]
         (when @open?
           [:div.time-picker-dropdown
            {:on-click #(.stopPropagation %)}
            [:div.time-picker-columns
             [:div.time-picker-column
              [:div.time-picker-column-label "H"]
              [:div.time-picker-values
               (for [h (range 24)]
                 ^{:key h}
                 [:button.time-picker-value
                  {:class (when (= h current-hour) "selected")
                   :on-click (fn [e]
                               (.stopPropagation e)
                               (on-change (format-time h (or current-minute 0))))}
                  (.padStart (str h) 2 "0")])]]
             [:div.time-picker-column
              [:div.time-picker-column-label "M"]
              [:div.time-picker-values
               (for [m (range 0 60 5)]
                 ^{:key m}
                 [:button.time-picker-value
                  {:class (when (= m current-minute) "selected")
                   :on-click (fn [e]
                               (.stopPropagation e)
                               (on-change (format-time (or current-hour 0) m)))}
                  (.padStart (str m) 2 "0")])]]]
            [:div.time-picker-actions
             (when (and show-clear? (seq time-val))
               [:button.time-picker-clear
                {:on-click (fn [e]
                             (.stopPropagation e)
                             (on-change nil)
                             (close!))}
                "Clear"])
             [:button.time-picker-close
              {:on-click (fn [e]
                           (.stopPropagation e)
                           (close!))}
              "Done"]]])]))))

(defn time-picker [task & {:keys [show-clear?] :or {show-clear? false}}]
  [generic-time-picker task :show-clear? show-clear?])

(defn item-edit-form
  [{:keys [title-atom description-atom tags-atom
           badge-title-atom badge-title-placeholder
           title-placeholder description-placeholder tags-placeholder
           on-save on-cancel on-delete]}]
  [:div.item-edit-form
   [:input {:type "text"
            :auto-complete "off"
            :value @title-atom
            :on-change #(reset! title-atom (-> % .-target .-value))
            :placeholder title-placeholder}]
   [:textarea {:value @description-atom
               :on-change #(reset! description-atom (-> % .-target .-value))
               :placeholder description-placeholder
               :rows 3}]
   (when tags-atom
     [:input {:type "text"
              :auto-complete "off"
              :value @tags-atom
              :on-change #(reset! tags-atom (-> % .-target .-value))
              :placeholder tags-placeholder}])
   (when badge-title-atom
     [:input {:type "text"
              :auto-complete "off"
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
