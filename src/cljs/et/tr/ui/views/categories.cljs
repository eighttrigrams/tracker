(ns et.tr.ui.views.categories
  (:require [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.i18n :refer [t]]
            [et.tr.filters :as filters]))

(defn add-entity-form [placeholder add-fn name-atom]
  [:div.add-entity-form
   [:input {:type "text"
            :auto-complete "off"
            :placeholder placeholder
            :value @name-atom
            :on-change #(reset! name-atom (-> % .-target .-value))
            :on-key-down #(when (= (.-key %) "Enter")
                            (add-fn @name-atom (fn [] (reset! name-atom ""))))}]
   [:button {:on-click #(add-fn @name-atom (fn [] (reset! name-atom "")))}
    "+"]])

(defn category-item [item category-type _update-fn state-key]
  (let [drag-cat (:drag-category @state/*app-state)
        drag-over-cat (:drag-over-category @state/*app-state)
        is-dragging (and drag-cat
                         (= (:type drag-cat) state-key)
                         (= (:id drag-cat) (:id item)))
        is-drag-over (and drag-over-cat
                          (= (:type drag-over-cat) state-key)
                          (= (:id drag-over-cat) (:id item)))]
    [:li {:class (str (when is-dragging "dragging")
                      (when is-drag-over " drag-over"))
          :draggable true
          :on-click #(state/set-editing-modal (keyword (str "category-" category-type)) item)
            :on-drag-start (fn [e]
                             (.setData (.-dataTransfer e) "text/plain" (str (:id item)))
                             (state/set-drag-category state-key (:id item)))
            :on-drag-end (fn [_]
                           (state/clear-category-drag-state))
            :on-drag-over (fn [e]
                            (.preventDefault e)
                            (state/set-drag-over-category state-key (:id item)))
            :on-drag-leave (fn [_]
                             (when (and drag-over-cat
                                        (= (:type drag-over-cat) state-key)
                                        (= (:id drag-over-cat) (:id item)))
                               (state/set-drag-over-category nil nil)))
            :on-drop (fn [e]
                       (.preventDefault e)
                       (when (and drag-cat
                                  (= (:type drag-cat) state-key)
                                  (not= (:id drag-cat) (:id item)))
                         (let [rect (.getBoundingClientRect (.-currentTarget e))
                               y (.-clientY e)
                               mid-y (+ (.-top rect) (/ (.-height rect) 2))
                               position (if (< y mid-y) "before" "after")]
                           (state/reorder-category state-key (:id drag-cat) (:id item) position))))}
       [:span.category-name (:name item)]
       (when (seq (:description item))
         [:span.category-description [task-item/markdown (:description item)]])]))

(defn- category-card [item category-type state-key]
  (let [expanded? (let [exp (:categories-page/expanded @state/*app-state)]
                    (and exp (= (:type exp) state-key) (= (:id exp) (:id item))))]
    [:div.category-card {:class (when expanded? "expanded")}
     [:div.category-card-header
      {:on-click #(state/toggle-category-item-expanded state-key (:id item))}
      [:span.category-card-name (:name item)]
      (when (seq (:badge_title item))
        [:span.category-card-badge (:badge_title item)])
      (when (seq (:tags item))
        [:span.category-card-tags (:tags item)])]
     (when expanded?
       [:div.category-card-body
        (if (seq (:description item))
          [task-item/clampable-description
           {:text (:description item)
            :on-click #(state/set-editing-modal (keyword (str "category-" category-type)) item)}]
          [:button.edit-icon.description-placeholder
           {:on-click (fn [e]
                        (.stopPropagation e)
                        (state/set-editing-modal (keyword (str "category-" category-type)) item))}
           "✎"])])]))

(defn category-cards-page [category-type]
  (let [name-atom (r/atom "")]
    (fn [category-type]
      (let [items (get @state/*app-state category-type)
            [add-fn add-label category-type-str]
            (case category-type
              :people  [state/add-person  :category/add-person  state/CATEGORY-TYPE-PERSON]
              :places  [state/add-place   :category/add-place   state/CATEGORY-TYPE-PLACE]
              :projects [state/add-project :category/add-project state/CATEGORY-TYPE-PROJECT]
              :goals   [state/add-goal    :category/add-goal    state/CATEGORY-TYPE-GOAL])]
        [:div.category-cards-page {:key (name category-type)}
         [:div.category-cards-toolbar
          [add-entity-form (t add-label) add-fn name-atom]]
         [:div.category-cards-grid
          (if (empty? items)
            [:div.category-cards-empty (t :category/no-results)]
            (doall
             (for [item items]
               ^{:key (:id item)}
               [category-card item category-type-str category-type])))]]))))
