(ns et.tr.ui.views.resources
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.tr.ui.state :as state]
            [et.tr.ui.state.resources :as resources-state]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.i18n :refer [t]]))

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

(defn- resource-edit-form [resource]
  (let [title (r/atom (:title resource))
        link (r/atom (:link resource))
        description (r/atom (or (:description resource) ""))
        tags (r/atom (or (:tags resource) ""))]
    (fn []
      [:div.item-edit-form
       [:input {:type "text"
                :value @title
                :on-change #(reset! title (-> % .-target .-value))
                :placeholder (t :resources/title-placeholder)}]
       [:input {:type "text"
                :value @link
                :on-change #(reset! link (-> % .-target .-value))
                :placeholder (t :resources/link-placeholder)}]
       [:textarea {:value @description
                   :on-change #(reset! description (-> % .-target .-value))
                   :placeholder (t :task/description-placeholder)
                   :rows 3}]
       [:input {:type "text"
                :value @tags
                :on-change #(reset! tags (-> % .-target .-value))
                :placeholder (t :task/tags-placeholder)}]
       [:div.edit-buttons
        [:button {:on-click (fn []
                              (state/update-resource (:id resource) @title @link @description @tags
                                                     state/clear-editing-resource))}
         (t :task/save)]
        [:button.cancel {:on-click #(state/clear-editing-resource)}
         (t :task/cancel)]]])))

(defn- resource-expanded-view [resource]
  (let [video-id (youtube-video-id (:link resource))]
    [:div.item-details
     (when video-id
       [youtube-embed video-id])
     [:div.resource-link
      [:a {:href (:link resource) :target "_blank" :rel "noopener noreferrer"}
       (:link resource)]]
     (when (seq (:description resource))
       [:div.item-description [task-item/markdown (:description resource)]])
     [:div.item-actions
      [resource-scope-selector resource]
      [resource-importance-selector resource]
      [:button.delete-btn {:on-click #(state/set-confirm-delete-resource resource)}
       (t :task/delete)]]]))

(defn- resource-header [resource is-expanded]
  (let [importance (:importance resource)]
    [:div.item-header
     {:on-click #(state/set-expanded-resource (when-not is-expanded (:id resource)))}
     [:div.item-title
      (when (and importance (not= importance "normal"))
        [:span.importance-badge {:class importance}
         (case importance "important" "★" "critical" "★★" nil)])
      (:title resource)
      (when is-expanded
        [:button.edit-icon {:on-click (fn [e]
                                        (.stopPropagation e)
                                        (state/set-editing-resource (:id resource)))}
         "✎"])]
     [:div.item-date
      [:a.resource-link-icon
       {:href (:link resource)
        :target "_blank"
        :rel "noopener noreferrer"
        :on-click #(.stopPropagation %)}
       "🔗"]]]))

(defn- resource-item [resource expanded-id editing-id]
  (let [is-expanded (= expanded-id (:id resource))
        is-editing (= editing-id (:id resource))]
    [:li {:class (when is-expanded "expanded")}
     (if is-editing
       [resource-edit-form resource]
       [:<>
        [resource-header resource is-expanded]
        (when is-expanded
          [resource-expanded-view resource])])]))

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

(defn resources-tab []
  (let [{:keys [resources]} @state/*app-state
        {:keys [expanded-resource editing-resource]} @resources-state/*resources-page-state]
    [:div.resources-page
     [:div.tasks-header
      [:h2 (t :nav/resources)]
      [importance-filter-toggle]]
     [search-add-form]
     (if (empty? resources)
       [:p.empty-message (t :resources/no-resources)]
       [:ul.items
        (for [resource resources]
          ^{:key (:id resource)}
          [resource-item resource expanded-resource editing-resource])])]))
