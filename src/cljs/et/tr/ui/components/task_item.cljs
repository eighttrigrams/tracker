(ns et.tr.ui.components.task-item
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.tr.ui.state :as state]
            [et.tr.ui.constants :as constants]
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

(defn inline-markdown
  "Markdown's *inline* constructs only - bold, italics, code, links - for the
  places that are one line and have to stay one line. A card title, above all.

  `marked` proper is the wrong tool there: it parses blocks, so it wraps whatever
  it is given in a <p>, and a <p> inside a flex row of title, badges and toolbar
  lays out nothing like the span it replaced. `parseInline` does the same
  emphasis, code and link parsing and returns a fragment, which is what a title
  wants.

  A <span> and not a <div>, for the same reason.

  This carries exactly the same trust as `markdown` above and no more: marked
  passes raw HTML through, so a title is as privileged as a description already
  was. It is the existing bargain in this codebase, not a new one - but it is a
  bargain, and it is worth knowing that it is now also made for titles."
  [text]
  [:span.md-inline
   {:dangerouslySetInnerHTML (r/unsafe-html (.parseInline marked (or text "")))}])

(defn- body-renderer [content-type]
  (if (= content-type "html") html markdown))

(defn- markdown-blocks [text]
  (str/split (or text "") #"\r?\n\r?\n+"))

(defn- modified-click?
  "Any modifier held. A click on a description is the card's own gesture — it
  opens the edit modal — but with a modifier the click belongs to the browser:
  cmd/ctrl on a link in the rendered markdown opens it in a new tab, shift in a
  window, alt saves it. Answering those with the modal on top would bury the very
  thing the gesture asked for, and the rule is kept blunt on purpose (any
  modifier, not a list of the ones we happen to know) so a platform binding we
  never thought of is not swallowed either."
  [e]
  (or (.-metaKey e) (.-ctrlKey e) (.-shiftKey e) (.-altKey e)))

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
                       (when (and (.. js/window getSelection -isCollapsed)
                                  (not (modified-click? e)))
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

(defn badge-click
  "Gate matrix for a category badge. Shift-click adds a negative filter, but
  only from a clean slate — no positive filter selected in any of the four
  groups — or once negatives already exist. Where it is closed the shift-click
  is refused, not folded into the plain-click path the way the mail-sender and
  resource-domain idioms do it: on a badge that fall-through would narrow the
  list to the very category the gesture asked to hide. A plain click toggles the
  positive filter for the badge's type as before, and goes inert while any
  negative filter is active. Shift+Option adds the badge's category with the
  category rules bypassed; unlike the plain click it stays open while a filter
  of the badge's own type is selected, since adding to an existing selection is
  what the gesture is for. The matrix itself is `filters/badge-gesture`.
  Returns the click handler. Every badge gets one and every badge shows the
  pointer cursor, because some gesture is open in every state the gate can be
  in: Shift+Option while no negative filter is up, the shift-exclude once one
  is. Which clicks the badge keeps off the row it sits in is
  `filters/badge-consumes-click?` — it does not keep the ones it has nothing to
  do with."
  [category toggle-fn has-filter-fn]
  (let [gate {:negative-active? (state/negative-filter-active?)
              :any-filters? (state/has-active-filters?)
              :type-filtered? (boolean (has-filter-fn (:type category)))}]
    (fn [e]
      (let [modifiers {:shift? (.-shiftKey e) :alt? (.-altKey e)}]
        (when (filters/badge-consumes-click? modifiers gate)
          (.stopPropagation e))
        (case (filters/badge-gesture modifiers gate)
          :bypass (toggle-fn (:type category) (:id category) true)
          :exclude (state/toggle-negative-filter (:type category) (:id category) (:name category))
          :toggle (toggle-fn (:type category) (:id category))
          nil)
        ;; The list has just narrowed under the badge, so the cursor goes where
        ;; the next thing is typed — the same move the sidebar's badges already
        ;; make. Which clicks earn it is `filters/refocus-search-after-badge-click?`,
        ;; so the rule has one home and can be tested without a DOM.
        (when (filters/refocus-search-after-badge-click? modifiers gate)
          (state/focus-page-search))))))

(defn category-badges [{:keys [item category-types toggle-fn has-filter-fn force-show?]}]
  (let [all-categories (mapcat (fn [[type k]] (map #(assoc % :type type) (get item k))) category-types)]
    (when (and (or force-show? (state/show-collapsed-categories?)) (seq all-categories))
      (into [:div.task-badges]
            (for [category all-categories]
              ^{:key (str (:type category) "-" (:id category))}
              [:span.tag {:class (:type category)
                          :style {:cursor "pointer"}
                          :on-click (badge-click category toggle-fn has-filter-fn)}
               (filters/badge-label category)])))))

(defn task-category-badges [task]
  (let [importance (:importance task)
        importance-stars (case importance
                           "important" "★"
                           "critical" "★★"
                           nil)
        all-types constants/category-type-pairs
        has-categories? (some #(seq (get task (second %))) all-types)
        show-categories? (and (state/show-collapsed-categories?) has-categories?)
        has-relations? (seq (:relations task))]
    (when (or importance-stars show-categories? has-relations?)
      [:div.task-badges
       (when has-relations?
         [relation-badges/relation-badges-collapsed (:relations task) "tsk" (:id task)])
       (when importance-stars
         [:span.importance-badge {:class importance} importance-stars])
       (when show-categories?
         (into [:<>]
               (for [category (mapcat (fn [[type k]] (map #(assoc % :type type) (get task k))) all-types)]
                 ^{:key (str (:type category) "-" (:id category))}
                 [:span.tag {:class (:type category)
                             :style {:cursor "pointer"}
                             :on-click (badge-click category state/toggle-shared-filter state/has-filter-for-type?)}
                  (filters/badge-label category)])))])))

(defn working-on-indicator
  "The pulsing dot marking the one task being worked on today. Rendered wherever
  a task title renders — only the control that sets the marker is Today-scoped."
  [task]
  (when (= (:id task) (state/working-on-task-id))
    [:span.working-on-indicator]))

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
    (let [task-categories (get task* (constants/category-type->key category-type*) [])]
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
    (let [current (get meet* (constants/category-type->key category-type*) [])]
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

(defn issue-category-selector [_issue _category-type _entities _label]
  (fn [issue* category-type* entities* label*]
    (let [current (get issue* (constants/category-type->key category-type*) [])]
      [category-selector/category-selector
       {:entity issue*
        :entity-id-key :id
        :category-type category-type*
        :entities entities*
        :label label*
        :current-categories current
        :on-categorize #(state/categorize-issue (:id issue*) category-type* %)
        :on-uncategorize #(state/uncategorize-issue (:id issue*) category-type* %)
        :on-close-focus-fn nil
        :open-selector-state (:category-selector/open @state/*app-state)
        :search-state (:category-selector/search @state/*app-state)
        :open-selector-fn state/open-category-selector
        :close-selector-fn state/close-category-selector
        :set-search-fn state/set-category-selector-search}])))

(defn journal-entry-category-selector [_entry _category-type _entities _label]
  (fn [entry* category-type* entities* label*]
    (let [current (get entry* (constants/category-type->key category-type*) [])]
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
