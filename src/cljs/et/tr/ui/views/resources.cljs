(ns et.tr.ui.views.resources
  (:require [reagent.core]
            [et.tr.ui.state :as state]
            [et.tr.ui.state.resources :as resources-state]
            [et.tr.ui.state.journals :as journals-state]
            [et.tr.ui.state.journal-entries :as journal-entries-state]
            [et.tr.ui.date :as date]
            [et.tr.ui.modals :as modals]
            [et.tr.ui.components.item-card :as item-card]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.ui.components.drag-drop :as drag-drop]
            [et.tr.ui.components.filter-section :as filter-section]
            [et.tr.ui.components.category-selector :as category-selector]
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

(defn- resource-item [resource expanded-id people places projects goals drag-enabled? drag-resource drag-over-resource]
  (let [is-expanded (= expanded-id (:id resource))
        is-dragging (= drag-resource (:id resource))
        is-drag-over (= drag-over-resource (:id resource))
        has-link? (seq (:link resource))
        domain (when has-link? (extract-domain (:link resource)))
        video-id (when has-link? (youtube-video-id (:link resource)))]
    [item-card/item-card
     {:item resource
      :expanded? is-expanded
      :on-toggle #(if is-expanded
                    (state/set-expanded-resource nil)
                    (state/expand-resource (:id resource) resource))
      :container {:tag :li
                  :class (str (when is-dragging "dragging")
                              (when is-drag-over " drag-over")
                              (when-not drag-enabled? " drag-disabled"))
                  :attrs {:draggable drag-enabled?
                          :on-drag-start (drag-drop/make-drag-start-handler resource state/set-drag-resource drag-enabled?)
                          :on-drag-end (fn [_] (state/clear-resource-drag-state))
                          :on-drag-over (drag-drop/make-drag-over-handler resource state/set-drag-over-resource drag-enabled?)
                          :on-drag-leave (drag-drop/make-drag-leave-handler drag-over-resource resource #(state/set-drag-over-resource nil))
                          :on-drop (drag-drop/make-drop-handler drag-resource resource state/reorder-resource drag-enabled?)}}
      :relation-link [:resource (:id resource)]
      :inline-edit (item-card/make-inline-edit
                     {:edit-id-path :resources-page/inline-edit-resource
                      :title-path :resources-page/inline-edit-title
                      :update-fn state/update-resource
                      :build-args (fn [item title-value done-cb]
                                    [(:id item) title-value (:link item) (:description item) (:tags item) done-cb])})
      :badges {:importance? true}
      :title-extra (if domain
                     [:span.mail-sender
                      {:on-click (fn [e]
                                   (.stopPropagation e)
                                   (if (and (.-shiftKey e)
                                            (nil? (:domain-filter @resources-state/*resources-page-state)))
                                     (state/toggle-resource-excluded-domain domain)
                                     (state/set-resource-domain-filter domain)))}
                      domain]
                     (when-not has-link?
                       [:span.mail-sender
                        {:on-click (fn [e]
                                     (.stopPropagation e)
                                     (if (and (.-shiftKey e)
                                              (nil? (:domain-filter @resources-state/*resources-page-state)))
                                       (state/toggle-resource-excluded-domain "Sheet")
                                       (state/set-resource-domain-filter "Sheet")))}
                        "Sheet"]))
      :date (when has-link?
              {:render (fn [_]
                         [:a.resource-link-icon
                          {:href (:link resource)
                           :target "_blank"
                           :rel "noopener noreferrer"
                           :on-click #(.stopPropagation %)}
                          "🔗"])})
      :description {:edit-type :resource
                    :on-edit state/edit-resource-description
                    :loaded-fn #(contains? % :description)}
      :expanded-prefix (when video-id [youtube-embed video-id])
      :categories {:selector-fn resource-category-selector :relations-prefix "res"}
      :footer {:left [{:type :scope :value (:scope resource)
                       :on-set #(state/set-resource-scope (:id resource) %)}
                      {:type :importance :value (:importance resource)
                       :on-set #(state/set-resource-importance (:id resource) %)}]
               :right [{:type :delete :on-click #(state/set-confirm-delete-resource resource)}]}}]))

(defn- sort-mode-toggle []
  (let [sort-mode (:sort-mode @resources-state/*resources-page-state)]
    [:div.sort-toggle.toggle-group
     [:button {:class (when (= sort-mode :recent) "active")
               :on-click #(when (not= sort-mode :recent) (state/set-resource-sort-mode :recent))}
      (t :resources/sort-recent)]
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
    :filter-state-key :shared/filter-goals
    :category-type state/CATEGORY-TYPE-GOAL}])

(defn- sidebar-filters []
  (let [app-state @state/*app-state
        collapsed-filters (:resources-page/collapsed-filters app-state)]
    (into [:div.sidebar [filter-section/category-badge-toggle]]
          (for [{:keys [filter-key title-key items-key filter-state-key category-type]} resources-sidebar-filter-configs]
            [resources-filter-section {:title (t title-key)
                                       :filter-key filter-key
                                       :items (get app-state items-key)
                                       :selected-ids (get app-state filter-state-key)
                                       :toggle-fn #(state/toggle-shared-filter category-type %)
                                       :clear-fn #(state/clear-shared-filter category-type)
                                       :collapsed? (contains? collapsed-filters filter-key)}]))))

(defn- journals-toggle []
  (let [journals-mode (:resources-page/journals-mode @state/*app-state)]
    [:div.series-mode-toggle.toggle-group
     [:button {:class (when journals-mode "active")
               :on-click #(state/toggle-journals-mode)}
      (t :journals/journals)]]))

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

(defn- journal-date-render [journal]
  [:<>
   [:span.schedule-type-badge (if (= (:schedule_type journal) "weekly") (t :journals/weekly) (t :journals/daily))]
   [:button.series-filter-btn {:on-click (fn [e]
                                           (.stopPropagation e)
                                           (state/set-journal-filter journal))
                               :title (t :journals/filter-by-journal)}
    "⏚"]])

(defn- journal-create-entry-button [journal]
  [:button.create-next-meeting-btn
   {:on-click (fn [e]
                (.stopPropagation e)
                (state/open-create-date-modal :journal journal))}
   (t :journals/create-entry)])

(defn- journal-item [journal expanded-id people places projects goals]
  (let [is-expanded (= expanded-id (:id journal))]
    [item-card/item-card
     {:item journal
      :expanded? is-expanded
      :on-toggle #(state/set-expanded-journal (when-not is-expanded (:id journal)))
      :inline-edit (item-card/make-inline-edit
                     {:edit-id-path :journals-page/inline-edit-journal
                      :title-path :journals-page/inline-edit-title
                      :update-fn state/update-journal})
      :date {:render journal-date-render}
      :description {:edit-type :journal}
      :categories {:selector-fn journal-category-selector}
      :footer {:left [{:type :scope :value (:scope journal)
                       :on-set #(state/set-journal-scope (:id journal) %)}]
               :right [{:type :delete :on-click #(state/set-confirm-delete-journal journal)}]}
      :readonly-extra [journal-create-entry-button journal]}]))

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
        (if (seq (:description entry))
          [:div.journal-entry-summary-description
           {:on-click #(state/set-editing-modal :journal-entry entry)}
           [task-item/markdown (:description entry)]]
          [:button.edit-icon.description-placeholder
           {:on-click (fn [e]
                        (.stopPropagation e)
                        (state/set-editing-modal :journal-entry entry))}
           "✎"])])]))

(defn- journal-filter-bar []
  (let [jf (state/journal-filter)
        summary-mode? (:resources-page/journal-summary-mode @state/*app-state)]
    [:div.series-filter-bar
     [:span.series-filter-label
      {:on-click #(state/open-filter-target-edit-modal :journal "/api/journals/" (:id jf))
       :style {:cursor "pointer"}}
      (:title jf)]
     [:button.journal-summary-btn
      {:class (when summary-mode? "active")
       :on-click #(swap! state/*app-state update :resources-page/journal-summary-mode not)}
      "📋"]
     [:button.clear-search {:on-click #(state/clear-journal-filter)} "x"]]))

(defn- journal-entry-date-render [entry]
  [:<>
   (when (:entry_date entry)
     [:span.due-date (date/format-date-localized (:entry_date entry))])
   (when (and (:journal_id entry) (not= (:id (state/journal-filter)) (:journal_id entry)))
     [:span.recurrence-icon {:on-click (fn [e]
                                         (.stopPropagation e)
                                         (state/set-journal-filter {:id (:journal_id entry) :title (:title entry)}))}
      "🔁"])])

(defn- journal-entry-item [entry expanded-id people places projects goals]
  (let [is-expanded (= expanded-id (:id entry))]
    [item-card/item-card
     {:item entry
      :expanded? is-expanded
      :on-toggle #(state/set-expanded-journal-entry (when-not is-expanded (:id entry)))
      :relation-link [:journal-entry (:id entry)]
      :inline-edit (item-card/make-inline-edit
                     {:edit-id-path :journal-entries-page/inline-edit-entry
                      :title-path :journal-entries-page/inline-edit-title
                      :update-fn state/update-journal-entry})
      :badges {:importance? true}
      :title-expanded-click (fn [e] (state/set-editing-modal :journal-entry e))
      :date {:render journal-entry-date-render}
      :description {:edit-type :journal-entry}
      :categories {:selector-fn resource-category-selector :relations-prefix "jen"}
      :footer {:left [{:type :scope :value (:scope entry)
                       :on-set #(state/set-journal-entry-scope (:id entry) %)}
                      {:type :importance :value (:importance entry)
                       :on-set #(state/set-journal-entry-importance (:id entry) %)}]
               :right [{:type :delete :on-click #(state/set-confirm-delete-journal-entry entry)}]}}]))

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
           [:<>
            [:ul.items
             (for [resource resources]
               ^{:key (:id resource)}
               [resource-item resource expanded-resource people places projects goals drag-enabled? drag-resource drag-over-resource])]
            (when (:has-more? @resources-state/*resources-page-state)
              [:div.load-more
               [:button.load-more-btn {:on-click #(state/load-more-resources)}
                (t :resources/see-more)]])])])]]))
