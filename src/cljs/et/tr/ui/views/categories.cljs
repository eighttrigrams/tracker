(ns et.tr.ui.views.categories
  (:require [et.tr.ui.state :as state]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.i18n :refer [t]]))

;; Handlers derive category-type from live app-state instead of closure
;; capture: Reagent re-renders on rAF, so a fast click after a tab switch
;; (or fill+click pair) can otherwise fire a handler that closed over the
;; previous render's category-type or empty input value.
(def ^:private active-tab->category-type
  {:cat-people :people :cat-places :places :cat-projects :projects :cat-goals :goals})

(def ^:private category-type->add-fn
  {:people state/add-person :places state/add-place
   :projects state/add-project :goals state/add-goal})

(defn- current-category-type []
  (active-tab->category-type (:active-tab @state/*app-state)))

(defn- current-search-value []
  (when-let [ct (current-category-type)]
    (or (get-in @state/*app-state [:categories-page/filter-search ct]) "")))

(defn combined-search-add-form [_category-type placeholder]
  (let [clear-search #(when-let [ct (current-category-type)]
                        (state/set-categories-filter-search ct ""))
        do-add (fn []
                 (let [ct (current-category-type)
                       v (current-search-value)
                       add-fn (category-type->add-fn ct)]
                   (when (and (seq v) add-fn)
                     (add-fn v clear-search))))
        input-value (or (current-search-value) "")]
    [:div.combined-search-add-form
     [:input {:type "text"
              :auto-complete "off"
              :placeholder placeholder
              :value input-value
              :on-change #(when-let [ct (current-category-type)]
                            (state/set-categories-filter-search ct (-> % .-target .-value)))
              :on-key-down (fn [e]
                             (cond
                               (= (.-key e) "Enter")
                               (do (.preventDefault e) (do-add))

                               (= (.-key e) "Escape")
                               (clear-search)))}]
     [:button {:on-click do-add}
      (t :tasks/add-button)]
     (when (seq input-value)
       [:button.clear-search {:on-click clear-search} "x"])]))

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
  (let [items (get @state/*app-state category-type)
        [add-label category-type-str]
        (case category-type
          :people  [:category/search-or-add-person  state/CATEGORY-TYPE-PERSON]
          :places  [:category/search-or-add-place   state/CATEGORY-TYPE-PLACE]
          :projects [:category/search-or-add-project state/CATEGORY-TYPE-PROJECT]
          :goals   [:category/search-or-add-goal    state/CATEGORY-TYPE-GOAL])]
    [:div.category-cards-page {:key (name category-type)}
     [combined-search-add-form category-type (t add-label)]
     [:div.category-cards-grid
      (if (empty? items)
        [:div.category-cards-empty (t :category/no-results)]
        (doall
         (for [item items]
           ^{:key (:id item)}
           [category-card item category-type-str category-type])))]]))
