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
          (sort-by (fn [[_id [_title count]]] count))
          reverse
          (map (fn [[id [title count]]]
                 [:li
                  {:key id
                   :on-click (select-secondary-context *state id)} 
                  (when (contains? selected-secondary-contexts-ids id) "!")
                  title
                  (str " (" count ")")])))]))

(defn component [_*state]
  (fn [*state]
    [:<>
     [:h2 "Search mode: " (:search_mode (:selected-context @*state))]
     [:h2 "Secondary contexts:"]
     [secondary-contexts-component *state]
     [:hr]
     [:> ReactMarkdown
      {:children (:description (:selected-context @*state))}]]))
