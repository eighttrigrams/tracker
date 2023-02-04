(ns ui.main.lhs.context-detail
  (:require [ui.actions :as actions]
            [ui.main.lhs.list-item :as list-item]
            ["react-markdown$default" :as ReactMarkdown]))

(defn item-component [*state]
  [:ul.cards
   [list-item/component *state (:selected-context @*state)]])

(defn count-issues [issues secondary-contexts]
  (let [count-reducer,,
        #(fn [count issue]
          (if (contains? (:contexts issue) %)
            (inc count)
            count))]
    (map (fn [[id title]]
           [id
            [title
             (reduce (count-reducer id) 0 issues)]])
         secondary-contexts)))

(defn- select-secondary-context [*state id]
  (fn [_]
    (swap! *state update :selected-secondary-contexts-ids
           #((if (contains? % id) disj conj) % id))
    (actions/change-secondary-contexts-selection! *state)))

(defn- secondary-contexts-component [*state]
  (let [{:keys                        [selected-secondary-contexts-ids
                                       issues]
         {:keys [secondary_contexts]} :selected-context} @*state]
    [:ul
     (->> secondary_contexts
          (count-issues issues)
          (sort-by (fn [[_id [title _count]]] (.toLowerCase title)))
          (map (fn [[id [title count]]]
                 [:li
                  {:key id
                   :on-click (select-secondary-context *state id)} 
                  [:span {:style (when (contains? selected-secondary-contexts-ids id)
                                   {:font-weight :bold})} title]
                  (when (empty? selected-secondary-contexts-ids)
                    (str " (" count ")"))])))]))

(defn- invert-secondary-contexts [*state]
  (fn [_]
    (swap! *state update :secondary-contexts-inverted? not)
    (actions/change-secondary-contexts-inverted! *state)))

(defn- invert-component [*state]
  [:span {:style (when (:secondary-contexts-inverted? @*state)
                   {:font-weight :bold})
          :on-click (invert-secondary-contexts *state)}
   "Invert"])

(defn component [_*state]
  (fn [*state]
    [:<>
     [:h2 "Search mode: " 
      (case (:search_mode (:selected-context @*state))
        0 "Normal"
        1 "A->Z,0->9"
        2 "9->0,Z->A")]
     (when (:secondary_contexts (:selected-context @*state))
       [:<>
        [:hr]
        [:h2 "Secondary contexts:"] 
        [invert-component *state]
        [secondary-contexts-component *state]])
     [:hr]
     [:> ReactMarkdown
      {:children (:description (:selected-context @*state))}]]))
