(ns et.tr.ui.views.categories
  (:require [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.components.task-item :as task-item]
            [et.tr.i18n :refer [t]]
            [et.tr.filters :as filters]))

(defn add-entity-form [placeholder add-fn name-atom]
  (fn []
    [:div.add-entity-form
     [:input {:type "text"
              :placeholder placeholder
              :value @name-atom
              :on-change #(reset! name-atom (-> % .-target .-value))
              :on-key-down #(when (= (.-key %) "Enter")
                              (add-fn @name-atom (fn [] (reset! name-atom ""))))}]
     [:button {:on-click #(add-fn @name-atom (fn [] (reset! name-atom "")))}
      "+"]]))

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

(defn- subtab-button [active-tab tab-key translation-key]
  [:button.subtab
   {:class (when (= active-tab tab-key) "active")
    :on-click #(state/set-active-tab tab-key)}
   (t translation-key)])

(defn- categories-subtabs []
  (let [raw-tab (:active-tab @state/*app-state)
        active-tab (if (= raw-tab :categories) :people-places raw-tab)]
    [:div.categories-subtabs
     [subtab-button active-tab :people-places :nav/people-places]
     [subtab-button active-tab :projects-goals :nav/projects-goals]]))

(defn- category-manage-section [items name-atom title-key add-label-key add-fn category-type update-fn state-key]
  (let [filtered-items (filters/filter-by-name items @name-atom)]
    [:div.manage-section
     [:h3 (t title-key)]
     [add-entity-form (t add-label-key) add-fn name-atom]
     [:ul.entity-list
      (doall
       (for [item filtered-items]
         ^{:key (:id item)}
         [category-item item category-type update-fn state-key]))]]))

(defn people-places-tab []
  (let [person-name (r/atom "")
        place-name (r/atom "")]
    (fn []
      (let [{:keys [people places]} @state/*app-state]
        [:div.manage-tab
         [category-manage-section people person-name :category/people :category/add-person
          state/add-person state/CATEGORY-TYPE-PERSON state/update-person :people]
         [category-manage-section places place-name :category/places :category/add-place
          state/add-place state/CATEGORY-TYPE-PLACE state/update-place :places]]))))

(defn projects-goals-tab []
  (let [project-name (r/atom "")
        goal-name (r/atom "")]
    (fn []
      (let [{:keys [projects goals]} @state/*app-state]
        [:div.manage-tab
         [category-manage-section projects project-name :category/projects :category/add-project
          state/add-project state/CATEGORY-TYPE-PROJECT state/update-project :projects]
         [category-manage-section goals goal-name :category/goals :category/add-goal
          state/add-goal state/CATEGORY-TYPE-GOAL state/update-goal :goals]]))))

(defn categories-tab []
  (let [active-tab (:active-tab @state/*app-state)]
    [:div.categories-page
     [categories-subtabs]
     (case active-tab
       :projects-goals [projects-goals-tab]
       [people-places-tab])]))
