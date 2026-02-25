(ns et.tr.ui.views.resources
  (:require [et.tr.ui.state :as state]
            [et.tr.ui.state.resources :as resources-state]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.ui.components.filter-section :as filter-section]
            [et.tr.ui.components.category-selector :as category-selector]
            [et.tr.ui.components.relation-link :as relation-link]
            [et.tr.ui.components.relation-badges :as relation-badges]
            [et.tr.i18n :refer [t]]))

(def ^:private resources-category-shortcut-keys
  {"Digit1" :people
   "Digit2" :places
   "Digit3" :projects})

(def resources-category-shortcut-numbers
  (into {} (map (fn [[k v]] [v (subs k 5)]) resources-category-shortcut-keys)))

(defn get-resources-category-shortcut-keys []
  resources-category-shortcut-keys)

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

(defn- resource-expanded-view [resource people places projects]
  (let [video-id (youtube-video-id (:link resource))]
    [:div.item-details
     (when video-id
       [youtube-embed video-id])
     [:div.resource-link
      [:a {:href (:link resource) :target "_blank" :rel "noopener noreferrer"}
       (:link resource)]]
     (when (seq (:description resource))
       [:div.item-description [task-item/markdown (:description resource)]])
     [:div.item-tags
      [resource-category-selector resource state/CATEGORY-TYPE-PERSON people (t :category/person)]
      [resource-category-selector resource state/CATEGORY-TYPE-PLACE places (t :category/place)]
      [resource-category-selector resource state/CATEGORY-TYPE-PROJECT projects (t :category/project)]
      [relation-badges/relation-badges-expanded (:relations resource) "res" (:id resource)]]
     [:div.item-actions
      [resource-scope-selector resource]
      [resource-importance-selector resource]
      [:div.combined-button-wrapper
       [:button.delete-btn {:on-click #(state/set-confirm-delete-resource resource)}
        (t :task/delete)]]]]))

(defn- resource-header [resource is-expanded]
  (let [importance (:importance resource)]
    [:div.item-header
     {:on-click #(state/set-expanded-resource (when-not is-expanded (:id resource)))}
     [:div.item-title
      [relation-link/relation-link-button :resource (:id resource)]
      (when (and importance (not= importance "normal"))
        [:span.importance-badge {:class importance}
         (case importance "important" "★" "critical" "★★" nil)])
      (:title resource)
      (when is-expanded
        [:button.edit-icon {:on-click (fn [e]
                                        (.stopPropagation e)
                                        (state/set-editing-modal :resource resource))}
         "✎"])]
     [:div.item-date
      [:a.resource-link-icon
       {:href (:link resource)
        :target "_blank"
        :rel "noopener noreferrer"
        :on-click #(.stopPropagation %)}
       "🔗"]]]))

(defn- resource-categories-readonly [resource]
  [task-item/category-badges
   {:item resource
    :category-types [[state/CATEGORY-TYPE-PERSON :people]
                     [state/CATEGORY-TYPE-PLACE :places]
                     [state/CATEGORY-TYPE-PROJECT :projects]]
    :toggle-fn state/toggle-shared-filter
    :has-filter-fn state/has-filter-for-type?}])

(defn- resource-item [resource expanded-id people places projects]
  (let [is-expanded (= expanded-id (:id resource))]
    [:li {:class (when is-expanded "expanded")}
     [resource-header resource is-expanded]
     (if is-expanded
       [resource-expanded-view resource people places projects]
       [resource-categories-readonly resource])]))

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
                        (and (.-altKey e) (= (.-key e) "Enter") (seq input-value))
                        (do
                          (.preventDefault e)
                          (state/add-resource input-value input-value
                                              #(state/set-resource-filter-search "")))

                        (= (.-key e) "Escape")
                        (state/set-resource-filter-search "")))}]
     [:button {:on-click #(when (seq input-value)
                            (state/add-resource input-value input-value
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
    :category-type state/CATEGORY-TYPE-PROJECT}])

(defn- sidebar-filters []
  (let [app-state @state/*app-state
        collapsed-filters (:resources-page/collapsed-filters app-state)]
    (into [:div.sidebar]
          (for [{:keys [filter-key title-key items-key filter-state-key category-type]} resources-sidebar-filter-configs]
            [resources-filter-section {:title (t title-key)
                                       :filter-key filter-key
                                       :items (get app-state items-key)
                                       :selected-ids (get app-state filter-state-key)
                                       :toggle-fn #(state/toggle-shared-filter category-type %)
                                       :clear-fn #(state/clear-shared-filter category-type)
                                       :collapsed? (contains? collapsed-filters filter-key)}]))))

(defn resources-tab []
  (let [{:keys [resources people places projects]} @state/*app-state
        {:keys [expanded-resource]} @resources-state/*resources-page-state]
    [:div.main-layout
     [sidebar-filters]
     [:div.main-content.resources-page
      [:div.tasks-header
       [:h2 (t :nav/resources)]
       [importance-filter-toggle]]
      [search-add-form]
      (if (empty? resources)
        [:p.empty-message (t :resources/no-resources)]
        [:ul.items
         (for [resource resources]
           ^{:key (:id resource)}
           [resource-item resource expanded-resource people places projects])])]]))
