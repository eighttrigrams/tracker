(ns et.tr.ui.views.resources
  (:require [reagent.core]
            [et.tr.ui.state :as state]
            [et.tr.ui.state.resources :as resources-state]
            [et.tr.ui.state.journals :as journals-state]
            [et.tr.ui.state.journal-entries :as journal-entries-state]
            [et.tr.ui.date :as date]
            [et.tr.ui.modals :as modals]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.ui.components.drag-drop :as drag-drop]
            [et.tr.ui.components.filter-section :as filter-section]
            [et.tr.ui.components.category-selector :as category-selector]
            [et.tr.ui.components.relation-link :as relation-link]
            [et.tr.ui.components.relation-badges :as relation-badges]
            [et.tr.i18n :refer [t]]))

(def ^:private resources-category-shortcut-keys
  {"Digit1" :people
   "Digit2" :places
   "Digit3" :projects
   "Digit4" :goals})

(def resources-category-shortcut-numbers
  (into {} (map (fn [[k v]] [v (subs k 5)]) resources-category-shortcut-keys)))

(defn get-resources-category-shortcut-keys []
  resources-category-shortcut-keys)

(defn- extract-domain [url]
  (when (string? url)
    (second (re-find #"https?://(?:www\.)?([^/]+)" url))))

(defn- youtube-video-id [url]
  (when (string? url)
    (or (second (re-find #"(?:youtube\.com/watch[^\s]*[?&]v=)([^&\s]+)" url))
        (second (re-find #"youtube\.com/shorts/([^?/\s]+)" url))
        (second (re-find #"youtu\.be/([^?/\s]+)" url)))))

(defn- youtube-embed [video-id]
  [:div.youtube-preview
   [:iframe
    {:width "420"
     :height "315"
     :src (str "https://www.youtube.com/embed/" video-id)
     :allowFullScreen true
     :frameBorder "0"}]])

(defn- resource-scope-selector [resource]
  (let [scope (or (:scope resource) "both")]
    [:div.task-scope-selector.toggle-group.compact
     (for [s ["private" "both" "work"]]
       ^{:key s}
       [:button.toggle-option
        {:class (when (= scope s) "active")
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-resource-scope (:id resource) s))}
        s])]))

(defn- resource-importance-selector [resource]
  (let [importance (or (:importance resource) "normal")]
    [:div.task-importance-selector.toggle-group.compact
     (for [[level label] [["normal" "○"] ["important" "★"] ["critical" "★★"]]]
       ^{:key level}
       [:button.toggle-option
        {:class (str level (when (= importance level) " active"))
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-resource-importance (:id resource) level))}
        label])]))

(defn- resource-category-selector [resource category-type entities label]
  (let [current-categories (case category-type
                             state/CATEGORY-TYPE-PERSON (:people resource)
                             state/CATEGORY-TYPE-PLACE (:places resource)
                             state/CATEGORY-TYPE-PROJECT (:projects resource)
                             state/CATEGORY-TYPE-GOAL (:goals resource)
                             [])]
    [category-selector/category-selector
     {:entity resource
      :entity-id-key :id
      :category-type category-type
      :entities entities
      :label label
      :current-categories current-categories
      :on-categorize #(state/categorize-resource (:id resource) category-type %)
      :on-uncategorize #(state/uncategorize-resource (:id resource) category-type %)
      :on-close-focus-fn nil
      :open-selector-state (:category-selector/open @state/*app-state)
      :search-state (:category-selector/search @state/*app-state)
      :open-selector-fn state/open-category-selector
      :close-selector-fn state/close-category-selector
      :set-search-fn state/set-category-selector-search}]))

(defn- resource-expanded-view [resource people places projects goals]
  (let [video-id (when (seq (:link resource)) (youtube-video-id (:link resource)))]
    [:div.item-details
     (when video-id
       [youtube-embed video-id])
     (if (seq (:description resource))
       [task-item/clampable-description
        {:text (:description resource)
         :on-click #(state/set-editing-modal :resource resource)}]
       [:button.edit-icon.description-placeholder
        {:on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-editing-modal :resource resource))}
        "✎"])
     [:div.item-tags
      [resource-category-selector resource state/CATEGORY-TYPE-PERSON people (t :category/person)]
      [resource-category-selector resource state/CATEGORY-TYPE-PLACE places (t :category/place)]
      [resource-category-selector resource state/CATEGORY-TYPE-PROJECT projects (t :category/project)]
      [resource-category-selector resource state/CATEGORY-TYPE-GOAL goals (t :category/goal)]
      [relation-badges/relation-badges-expanded (:relations resource) "res" (:id resource)]]
     [:div.item-actions
      [resource-scope-selector resource]
      [resource-importance-selector resource]
      [:div.combined-button-wrapper
       [:button.delete-btn {:on-click #(state/set-confirm-delete-resource resource)}
        (t :task/delete)]]]]))

(defn- resource-inline-title-edit [resource]
  (let [value (or (:resources-page/inline-edit-title @state/*app-state) "")]
    [:input.inline-title-edit
     {:type "text"
      :auto-focus true
      :value value
      :on-click #(.stopPropagation %)
      :on-change #(swap! state/*app-state assoc :resources-page/inline-edit-title (.. % -target -value))
      :on-key-down (fn [e]
                     (case (.-key e)
                       "Enter" (do (.stopPropagation e)
                                   (state/update-resource (:id resource) value (:link resource) (:description resource) (:tags resource)
                                     #(swap! state/*app-state dissoc :resources-page/inline-edit-resource :resources-page/inline-edit-title)))
                       "Escape" (do (.stopPropagation e)
                                    (swap! state/*app-state dissoc :resources-page/inline-edit-resource :resources-page/inline-edit-title))
                       nil))
      :on-blur (fn [_]
                 (state/update-resource (:id resource) value (:link resource) (:description resource) (:tags resource)
                   #(swap! state/*app-state dissoc :resources-page/inline-edit-resource :resources-page/inline-edit-title)))}]))

(defn- resource-header [resource is-expanded]
  (let [importance (:importance resource)
        has-link? (seq (:link resource))
        domain (when has-link? (extract-domain (:link resource)))
        inline-editing? (= (:resources-page/inline-edit-resource @state/*app-state) (:id resource))]
    [:div.item-header
     {:on-click (fn [_]
                  (when-not (or inline-editing?
                                (and is-expanded (not (.. js/window getSelection -isCollapsed))))
                    (state/set-expanded-resource (when-not is-expanded (:id resource)))))}
     [:div.item-title
      [relation-link/relation-link-button :resource (:id resource)]
      (when (and importance (not= importance "normal"))
        [:span.importance-badge {:class importance}
         (case importance "important" "★" "critical" "★★" nil)])
      (if domain
        [:span.mail-sender {:on-click (fn [e]
                                        (.stopPropagation e)
                                        (if (and (.-shiftKey e)
                                                 (nil? (:domain-filter @resources-state/*resources-page-state)))
                                          (state/toggle-resource-excluded-domain domain)
                                          (state/set-resource-domain-filter domain)))}
         domain]
        (when-not has-link?
          [:span.mail-sender {:on-click (fn [e]
                                          (.stopPropagation e)
                                          (if (and (.-shiftKey e)
                                                   (nil? (:domain-filter @resources-state/*resources-page-state)))
                                            (state/toggle-resource-excluded-domain "Sheet")
                                            (state/set-resource-domain-filter "Sheet")))}
           "Sheet"]))
      (if inline-editing?
        [resource-inline-title-edit resource]
        [:span.item-title-text
         {:on-click (fn [e]
                      (when (and is-expanded (.-altKey e))
                        (.stopPropagation e)
                        (swap! state/*app-state assoc
                          :resources-page/inline-edit-resource (:id resource)
                          :resources-page/inline-edit-title (:title resource))))}
         (:title resource)])]
     (when has-link?
       [:div.item-date
        [:a.resource-link-icon
         {:href (:link resource)
          :target "_blank"
          :rel "noopener noreferrer"
          :on-click #(.stopPropagation %)}
         "🔗"]])]))

(defn- resource-categories-readonly [resource]
  [:div.item-tags-readonly
   [task-item/category-badges
    {:item resource
     :category-types [[state/CATEGORY-TYPE-PERSON :people]
                      [state/CATEGORY-TYPE-PLACE :places]
                      [state/CATEGORY-TYPE-PROJECT :projects]
                      [state/CATEGORY-TYPE-GOAL :goals]]
     :toggle-fn state/toggle-shared-filter
     :has-filter-fn state/has-filter-for-type?}]
   (when (seq (:relations resource))
     [relation-badges/relation-badges-collapsed (:relations resource) "res" (:id resource)])])

(defn- resource-item [resource expanded-id people places projects goals drag-enabled? drag-resource drag-over-resource]
  (let [is-expanded (= expanded-id (:id resource))
        is-dragging (= drag-resource (:id resource))
        is-drag-over (= drag-over-resource (:id resource))]
    [:li {:class (str (when is-expanded "expanded")
                      (when is-dragging " dragging")
                      (when is-drag-over " drag-over")
                      (when-not drag-enabled? " drag-disabled"))
          :draggable drag-enabled?
          :on-drag-start (drag-drop/make-drag-start-handler resource state/set-drag-resource drag-enabled?)
          :on-drag-end (fn [_] (state/clear-resource-drag-state))
          :on-drag-over (drag-drop/make-drag-over-handler resource state/set-drag-over-resource drag-enabled?)
          :on-drag-leave (drag-drop/make-drag-leave-handler drag-over-resource resource #(state/set-drag-over-resource nil))
          :on-drop (drag-drop/make-drop-handler drag-resource resource state/reorder-resource drag-enabled?)}
     [resource-header resource is-expanded]
     (if is-expanded
       [resource-expanded-view resource people places projects goals]
       [resource-categories-readonly resource])]))

(defn- sort-mode-toggle []
  (let [sort-mode (:sort-mode @resources-state/*resources-page-state)]
    [:div.sort-toggle.toggle-group
     [:button {:class (when (= sort-mode :manual) "active")
               :on-click #(when (not= sort-mode :manual) (state/set-resource-sort-mode :manual))}
      (t :resources/sort-manual)]
     [:button {:class (when (= sort-mode :added) "active")
               :on-click #(when (not= sort-mode :added) (state/set-resource-sort-mode :added))}
      (t :resources/sort-added)]]))

(defn- importance-filter-toggle []
  (let [importance-filter (:importance-filter @resources-state/*resources-page-state)]
    [:div.importance-filter-toggle.toggle-group
     [:button {:class (when (nil? importance-filter) "active")
               :on-click #(state/set-resource-importance-filter nil)
               :title (t :importance/filter-off)}
      "○"]
     [:button {:class (str "important" (when (= importance-filter :important) " active"))
               :on-click #(state/set-resource-importance-filter :important)
               :title (t :importance/filter-important)}
      "★"]
     [:button {:class (str "critical" (when (= importance-filter :critical) " active"))
               :on-click #(state/set-resource-importance-filter :critical)
               :title (t :importance/filter-critical)}
      "★★"]]))

(defn- resource-domain-filter-badge []
  (let [domain-filter (:domain-filter @resources-state/*resources-page-state)
        excluded-domains (:excluded-domains @resources-state/*resources-page-state)]
    (when (or domain-filter (seq excluded-domains))
      [:div.mail-sender-filter
       (when domain-filter
         [:span.filter-item-label.included
          domain-filter
          [:button.remove-item {:on-click #(state/clear-resource-domain-filter)} "x"]])
       (for [domain excluded-domains]
         ^{:key domain}
         [:span.filter-item-label.excluded
          domain
          [:button.remove-item {:on-click #(state/clear-resource-excluded-domain domain)} "x"]])
       (when (>= (count excluded-domains) 2)
         [:button.remove-all-filters {:on-click #(state/clear-all-resource-filters)} "x"])])))

(defn- url? [s]
  (and (string? s)
       (re-find #"^https?://" s)))

(defn- add-resource-from-input [input-value on-success]
  (let [link (when (url? input-value) input-value)]
    (state/add-resource input-value link on-success)))

(defn- search-add-form []
  (let [input-value (:filter-search @resources-state/*resources-page-state)]
    [:div.combined-search-add-form
     [:input#resources-filter-search
      {:type "text"
       :auto-complete "off"
       :placeholder (t :resources/search-or-add)
       :value input-value
       :on-change #(state/set-resource-filter-search (-> % .-target .-value))
       :on-key-down (fn [e]
                      (cond
                        (and (= (.-key e) "Enter") (seq input-value))
                        (do
                          (.preventDefault e)
                          (add-resource-from-input input-value
                                                   #(state/set-resource-filter-search "")))

                        (= (.-key e) "Escape")
                        (state/set-resource-filter-search "")))}]
     [:button {:on-click #(when (seq input-value)
                            (add-resource-from-input input-value
                                                     (fn [] (state/set-resource-filter-search ""))))}
      (t :tasks/add-button)]
     (when (seq input-value)
       [:button.clear-search {:on-click #(state/set-resource-filter-search "")} "x"])]))

(defn- resources-filter-section [{:keys [title filter-key items selected-ids toggle-fn clear-fn collapsed?]}]
  [filter-section/category-filter-section {:title title
                                           :shortcut-number (resources-category-shortcut-numbers filter-key)
                                           :filter-key filter-key
                                           :items items
                                           :marked-ids selected-ids
                                           :toggle-fn toggle-fn
                                           :clear-fn clear-fn
                                           :collapsed? collapsed?
                                           :toggle-collapsed-fn state/toggle-resources-filter-collapsed
                                           :set-search-fn state/set-resources-category-search
                                           :search-state-path [:resources-page/category-search filter-key]
                                           :section-class (name filter-key)
                                           :item-active-class "active"
                                           :label-class nil
                                           :page-prefix "resources"}])

(def ^:private resources-sidebar-filter-configs
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
    :filter-state-key :resources-page/filter-goals
    :category-type state/CATEGORY-TYPE-GOAL}])

(defn- sidebar-filters []
  (let [app-state @state/*app-state
        collapsed-filters (:resources-page/collapsed-filters app-state)]
    (into [:div.sidebar]
          (for [{:keys [filter-key title-key items-key filter-state-key category-type]} resources-sidebar-filter-configs]
            [resources-filter-section {:title (t title-key)
                                       :filter-key filter-key
                                       :items (get app-state items-key)
                                       :selected-ids (get app-state filter-state-key)
                                       :toggle-fn (if (= category-type state/CATEGORY-TYPE-GOAL)
                                                    #(state/toggle-resources-goal-filter %)
                                                    #(state/toggle-shared-filter category-type %))
                                       :clear-fn (if (= category-type state/CATEGORY-TYPE-GOAL)
                                                   #(state/clear-resources-goal-filter)
                                                   #(state/clear-shared-filter category-type))
                                       :collapsed? (contains? collapsed-filters filter-key)}]))))

(defn- journals-toggle []
  (let [journals-mode (:resources-page/journals-mode @state/*app-state)]
    [:div.series-mode-toggle.toggle-group
     [:button {:class (when journals-mode "active")
               :on-click #(state/toggle-journals-mode)}
      (t :journals/journals)]]))

(defn- journal-scope-selector [journal]
  (let [scope (or (:scope journal) "both")]
    [:div.task-scope-selector.toggle-group.compact
     (for [s ["private" "both" "work"]]
       ^{:key s}
       [:button.toggle-option
        {:class (when (= scope s) "active")
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-journal-scope (:id journal) s))}
        s])]))

(defn- journal-category-selector [journal category-type entities label]
  (let [current-categories (case category-type
                             state/CATEGORY-TYPE-PERSON (:people journal)
                             state/CATEGORY-TYPE-PLACE (:places journal)
                             state/CATEGORY-TYPE-PROJECT (:projects journal)
                             state/CATEGORY-TYPE-GOAL (:goals journal)
                             [])]
    [category-selector/category-selector
     {:entity journal
      :entity-id-key :id
      :category-type category-type
      :entities entities
      :label label
      :current-categories current-categories
      :on-categorize #(state/categorize-journal (:id journal) category-type %)
      :on-uncategorize #(state/uncategorize-journal (:id journal) category-type %)
      :on-close-focus-fn nil
      :open-selector-state (:category-selector/open @state/*app-state)
      :search-state (:category-selector/search @state/*app-state)
      :open-selector-fn state/open-category-selector
      :close-selector-fn state/close-category-selector
      :set-search-fn state/set-category-selector-search}]))

(defn- journal-expanded-view [journal people places projects goals]
  [:div.item-details
   (if (seq (:description journal))
     [task-item/clampable-description
      {:text (:description journal)
       :on-click #(state/set-editing-modal :journal journal)}]
     [:button.edit-icon.description-placeholder
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (state/set-editing-modal :journal journal))}
      "✎"])
   [:div.item-tags
    [journal-category-selector journal state/CATEGORY-TYPE-PERSON people (t :category/person)]
    [journal-category-selector journal state/CATEGORY-TYPE-PLACE places (t :category/place)]
    [journal-category-selector journal state/CATEGORY-TYPE-PROJECT projects (t :category/project)]
    [journal-category-selector journal state/CATEGORY-TYPE-GOAL goals (t :category/goal)]]
   [:div.item-actions
    [journal-scope-selector journal]
    [:div.combined-button-wrapper
     [:button.delete-btn {:on-click #(state/set-confirm-delete-journal journal)}
      (t :task/delete)]]]])

(defn- journal-inline-title-edit [journal]
  (let [value (or (:journals-page/inline-edit-title @state/*app-state) "")]
    [:input.inline-title-edit
     {:type "text"
      :auto-focus true
      :value value
      :on-click #(.stopPropagation %)
      :on-change #(swap! state/*app-state assoc :journals-page/inline-edit-title (.. % -target -value))
      :on-key-down (fn [e]
                     (case (.-key e)
                       "Enter" (do (.stopPropagation e)
                                   (state/update-journal (:id journal) value (:description journal) (:tags journal)
                                     #(swap! state/*app-state dissoc :journals-page/inline-edit-journal :journals-page/inline-edit-title)))
                       "Escape" (do (.stopPropagation e)
                                    (swap! state/*app-state dissoc :journals-page/inline-edit-journal :journals-page/inline-edit-title))
                       nil))
      :on-blur (fn [_]
                 (state/update-journal (:id journal) value (:description journal) (:tags journal)
                   #(swap! state/*app-state dissoc :journals-page/inline-edit-journal :journals-page/inline-edit-title)))}]))

(defn- journal-header [journal is-expanded]
  (let [inline-editing? (= (:journals-page/inline-edit-journal @state/*app-state) (:id journal))]
    [:div.item-header
     {:on-click (fn [_]
                  (when-not (or inline-editing?
                                (and is-expanded (not (.. js/window getSelection -isCollapsed))))
                    (state/set-expanded-journal (when-not is-expanded (:id journal)))))}
     [:div.item-title
      (if inline-editing?
        [journal-inline-title-edit journal]
        [:span.item-title-text
         {:on-click (fn [e]
                      (when (and is-expanded (.-altKey e))
                        (.stopPropagation e)
                        (swap! state/*app-state assoc
                          :journals-page/inline-edit-journal (:id journal)
                          :journals-page/inline-edit-title (:title journal))))}
         (:title journal)])]
     [:div.item-date
    [:span.schedule-type-badge (if (= (:schedule_type journal) "weekly") (t :journals/weekly) (t :journals/daily))]
    [:button.series-filter-btn {:on-click (fn [e]
                                            (.stopPropagation e)
                                            (state/set-journal-filter journal))
                                 :title (t :journals/filter-by-journal)}
     "⏚"]]]))

(defn- journal-categories-readonly [journal]
  [:div.item-tags-readonly
   [task-item/category-badges
    {:item journal
     :category-types [[state/CATEGORY-TYPE-PERSON :people]
                      [state/CATEGORY-TYPE-PLACE :places]
                      [state/CATEGORY-TYPE-PROJECT :projects]
                      [state/CATEGORY-TYPE-GOAL :goals]]
     :toggle-fn state/toggle-shared-filter
     :has-filter-fn state/has-filter-for-type?}]])

(defn- journal-item [journal expanded-id people places projects goals]
  (let [is-expanded (= expanded-id (:id journal))]
    [:li {:class (when is-expanded "expanded")}
     [journal-header journal is-expanded]
     (if is-expanded
       [journal-expanded-view journal people places projects goals]
       [journal-categories-readonly journal])]))

(defn- journal-search-add-form []
  (let [input-value (:filter-search @journals-state/*journals-page-state)
        adding-mode (reagent.core/atom nil)]
    (fn []
      (let [input-value (:filter-search @journals-state/*journals-page-state)]
        [:div.combined-search-add-form
         [:input#resources-filter-search
          {:type "text"
           :auto-complete "off"
           :placeholder (t :journals/search-or-add)
           :value input-value
           :on-change #(state/set-journal-filter-search (-> % .-target .-value))
           :on-key-down (fn [e]
                          (cond
                            (and (= (.-key e) "Enter") (seq input-value))
                            (do
                              (.preventDefault e)
                              (reset! adding-mode input-value))

                            (= (.-key e) "Escape")
                            (state/set-journal-filter-search "")))}]
         [:button {:on-click #(when (seq input-value)
                                (reset! adding-mode input-value))}
          (t :tasks/add-button)]
         (when (seq input-value)
           [:button.clear-search {:on-click #(state/set-journal-filter-search "")} "x"])
         (when @adding-mode
           [:div.modal-overlay {:on-click #(reset! adding-mode nil)}
            [modals/modal-keyboard-shortcut {:on-confirm identity :on-escape #(reset! adding-mode nil) :enabled? false}]
            [:div.modal {:on-click #(.stopPropagation %)}
             [:div.modal-header (t :modal/journal-type-select)]
             [:div.modal-body
              [:div.schedule-mode-selector.toggle-group
               [:button.toggle-option
                {:on-click (fn [_]
                             (state/add-journal @adding-mode "daily"
                                                #(state/set-journal-filter-search ""))
                             (reset! adding-mode nil))}
                (t :modal/journal-type-daily)]
               [:button.toggle-option
                {:on-click (fn [_]
                             (state/add-journal @adding-mode "weekly"
                                                #(state/set-journal-filter-search ""))
                             (reset! adding-mode nil))}
                (t :modal/journal-type-weekly)]]]
             [:div.modal-footer
              [:button.cancel {:on-click #(reset! adding-mode nil)} (t :modal/cancel)]]]])]))))

(defn- journals-list []
  (let [{:keys [journals people places projects goals]} @state/*app-state
        {:keys [expanded-journal]} @journals-state/*journals-page-state]
    (if (empty? journals)
      [:p.empty-message (t :journals/no-journals)]
      [:ul.items
       (for [journal journals]
         ^{:key (:id journal)}
         [journal-item journal expanded-journal people places projects goals])])))

(defn- journal-entries-summary []
  (let [entries (:journal-entries @state/*app-state)]
    [:div.journal-entries-summary
     (for [entry entries]
       ^{:key (:id entry)}
       [:div.journal-entry-summary-item
        [:div.journal-entry-summary-header
         [:span.journal-entry-summary-title (:title entry)]
         (when (:entry_date entry)
           [:span.journal-entry-summary-date (date/format-date-localized (:entry_date entry))])]
        (when (seq (:description entry))
          [:div.journal-entry-summary-description (:description entry)])])]))

(defn- journal-filter-bar []
  (let [jf (state/journal-filter)
        summary-mode? (:resources-page/journal-summary-mode @state/*app-state)]
    [:div.series-filter-bar
     [:span.series-filter-label (:title jf)]
     [:button.journal-summary-btn
      {:class (when summary-mode? "active")
       :on-click #(swap! state/*app-state update :resources-page/journal-summary-mode not)}
      "📋"]
     [:button.clear-search {:on-click #(state/clear-journal-filter)} "x"]]))

(defn- journal-entry-scope-selector [entry]
  (let [scope (or (:scope entry) "both")]
    [:div.task-scope-selector.toggle-group.compact
     (for [s ["private" "both" "work"]]
       ^{:key s}
       [:button.toggle-option
        {:class (when (= scope s) "active")
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-journal-entry-scope (:id entry) s))}
        s])]))

(defn- journal-entry-importance-selector [entry]
  (let [importance (or (:importance entry) "normal")]
    [:div.task-importance-selector.toggle-group.compact
     (for [[level label] [["normal" "○"] ["important" "★"] ["critical" "★★"]]]
       ^{:key level}
       [:button.toggle-option
        {:class (str level (when (= importance level) " active"))
         :on-click (fn [e]
                     (.stopPropagation e)
                     (state/set-journal-entry-importance (:id entry) level))}
        label])]))

(defn- journal-entry-expanded-view [entry people places projects goals]
  [:div.item-details
   (if (seq (:description entry))
     [task-item/clampable-description
      {:text (:description entry)
       :on-click #(state/set-editing-modal :journal-entry entry)}]
     [:button.edit-icon.description-placeholder
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (state/set-editing-modal :journal-entry entry))}
      "✎"])
   [:div.item-tags
    [resource-category-selector entry state/CATEGORY-TYPE-PERSON people (t :category/person)]
    [resource-category-selector entry state/CATEGORY-TYPE-PLACE places (t :category/place)]
    [resource-category-selector entry state/CATEGORY-TYPE-PROJECT projects (t :category/project)]
    [resource-category-selector entry state/CATEGORY-TYPE-GOAL goals (t :category/goal)]
    [relation-badges/relation-badges-expanded (:relations entry) "jen" (:id entry)]]
   [:div.item-actions
    [journal-entry-scope-selector entry]
    [journal-entry-importance-selector entry]
    [:div.combined-button-wrapper
     [:button.delete-btn {:on-click #(state/set-confirm-delete-journal-entry entry)}
      (t :task/delete)]]]])

(defn- journal-entry-inline-title-edit [entry]
  (let [value (or (:journal-entries-page/inline-edit-title @state/*app-state) "")]
    [:input.inline-title-edit
     {:type "text"
      :auto-focus true
      :value value
      :on-click #(.stopPropagation %)
      :on-change #(swap! state/*app-state assoc :journal-entries-page/inline-edit-title (.. % -target -value))
      :on-key-down (fn [e]
                     (case (.-key e)
                       "Enter" (do (.stopPropagation e)
                                   (state/update-journal-entry (:id entry) value (:description entry) (:tags entry)
                                     #(swap! state/*app-state dissoc :journal-entries-page/inline-edit-entry :journal-entries-page/inline-edit-title)))
                       "Escape" (do (.stopPropagation e)
                                    (swap! state/*app-state dissoc :journal-entries-page/inline-edit-entry :journal-entries-page/inline-edit-title))
                       nil))
      :on-blur (fn [_]
                 (state/update-journal-entry (:id entry) value (:description entry) (:tags entry)
                   #(swap! state/*app-state dissoc :journal-entries-page/inline-edit-entry :journal-entries-page/inline-edit-title)))}]))

(defn- journal-entry-header [entry is-expanded]
  (let [importance (:importance entry)
        inline-editing? (= (:journal-entries-page/inline-edit-entry @state/*app-state) (:id entry))]
    [:div.item-header
     {:on-click (fn [_]
                  (when-not (or inline-editing?
                                (and is-expanded (not (.. js/window getSelection -isCollapsed))))
                    (state/set-expanded-journal-entry (when-not is-expanded (:id entry)))))}
     [:div.item-title
      [relation-link/relation-link-button :journal-entry (:id entry)]
      (when (and importance (not= importance "normal"))
        [:span.importance-badge {:class importance}
         (case importance "important" "★" "critical" "★★" nil)])
      (if inline-editing?
        [journal-entry-inline-title-edit entry]
        [:span.item-title-text
         {:on-click (fn [e]
                      (when (and is-expanded (.-altKey e))
                        (.stopPropagation e)
                        (swap! state/*app-state assoc
                          :journal-entries-page/inline-edit-entry (:id entry)
                          :journal-entries-page/inline-edit-title (:title entry))))}
         (:title entry)])]
     [:div.item-date
      (when (:entry_date entry)
        [:span.due-date (date/format-date-localized (:entry_date entry))])
      (when (and (:journal_id entry) (not= (:id (state/journal-filter)) (:journal_id entry)))
        [:span.recurrence-icon {:on-click (fn [e]
                                            (.stopPropagation e)
                                            (state/set-journal-filter {:id (:journal_id entry) :title (:title entry)}))}
         "🔁"])]]))

(defn- journal-entry-categories-readonly [entry]
  [:div.item-tags-readonly
   [task-item/category-badges
    {:item entry
     :category-types [[state/CATEGORY-TYPE-PERSON :people]
                      [state/CATEGORY-TYPE-PLACE :places]
                      [state/CATEGORY-TYPE-PROJECT :projects]
                      [state/CATEGORY-TYPE-GOAL :goals]]
     :toggle-fn state/toggle-shared-filter
     :has-filter-fn state/has-filter-for-type?}]
   (when (seq (:relations entry))
     [relation-badges/relation-badges-collapsed (:relations entry) "jen" (:id entry)])])

(defn- journal-entry-item [entry expanded-id people places projects goals]
  (let [is-expanded (= expanded-id (:id entry))]
    [:li {:class (when is-expanded "expanded")}
     [journal-entry-header entry is-expanded]
     (if is-expanded
       [journal-entry-expanded-view entry people places projects goals]
       [journal-entry-categories-readonly entry])]))

(defn- journal-entries-search-add-form []
  (let [input-value (:filter-search @journal-entries-state/*journal-entries-page-state)]
    [:div.combined-search-add-form
     [:input#resources-filter-search
      {:type "text"
       :auto-complete "off"
       :placeholder (t :journals/search-or-add-entry)
       :value input-value
       :on-change #(state/set-journal-entry-filter-search (-> % .-target .-value))
       :on-key-down (fn [e]
                      (cond
                        (and (= (.-key e) "Enter") (seq input-value))
                        (do
                          (.preventDefault e)
                          (state/add-journal-entry input-value
                                                   #(state/set-journal-entry-filter-search "")))

                        (= (.-key e) "Escape")
                        (state/set-journal-entry-filter-search "")))}]
     [:button {:on-click #(when (seq input-value)
                            (state/add-journal-entry input-value
                                                     (fn [] (state/set-journal-entry-filter-search ""))))}
      (t :tasks/add-button)]
     (when (seq input-value)
       [:button.clear-search {:on-click #(state/set-journal-entry-filter-search "")} "x"])]))

(defn- journal-entries-list []
  (let [{:keys [journal-entries people places projects goals]} @state/*app-state
        {:keys [expanded-entry]} @journal-entries-state/*journal-entries-page-state]
    (if (empty? journal-entries)
      [:p.empty-message (t :journals/no-entries)]
      [:ul.items
       (for [entry journal-entries]
         ^{:key (:id entry)}
         [journal-entry-item entry expanded-entry people places projects goals])])))

(defn resources-tab []
  (let [{:keys [resources people places projects goals drag-resource drag-over-resource]} @state/*app-state
        journals-mode (state/journals-mode?)
        journal-filter (state/journal-filter)
        {:keys [expanded-resource sort-mode]} @resources-state/*resources-page-state
        manual-mode? (= sort-mode :manual)
        any-open? (some? expanded-resource)
        drag-enabled? (and manual-mode? (not any-open?))]
    [:div.main-layout
     [sidebar-filters]
     [:div.main-content.resources-page
      [:div.tasks-header
       [journals-toggle]
       (when-not (or journals-mode journal-filter)
         [importance-filter-toggle])
       (when-not (or journals-mode journal-filter)
         [sort-mode-toggle])]
      (cond
        journals-mode
        [:<>
         [journal-search-add-form]
         [journals-list]]

        journal-filter
        [:<>
         [journal-filter-bar]
         (if (:resources-page/journal-summary-mode @state/*app-state)
           [journal-entries-summary]
           [journal-entries-list])]

        :else
        [:<>
         [search-add-form]
         [resource-domain-filter-badge]
         (if (empty? resources)
           [:p.empty-message (t :resources/no-resources)]
           [:ul.items
            (for [resource resources]
              ^{:key (:id resource)}
              [resource-item resource expanded-resource people places projects goals drag-enabled? drag-resource drag-over-resource])])])]]))
