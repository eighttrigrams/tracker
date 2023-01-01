(ns ui.main.lhs.context-detail
  (:require [ui.actions :as actions]
            [ui.main.lhs.list-item :as list-item]
            ["react-markdown$default" :as ReactMarkdown]))

(defn item-component [*state]
  [:ul.cards
   [list-item/component *state (:selected-context @*state)]])

(defn- secondary-contexts-component [*state]
  (let [{:keys                        [selected-secondary-contexts-ids]
         {:keys [secondary_contexts]} :selected-context} @*state]
    [:ul
     (doall
      (map (fn [[id title]]
             [:li
              {:key id
               :on-click (fn [_]
                           (swap! *state update :selected-secondary-contexts-ids 
                                  #((if (contains? % id) disj conj) % id))
                           (actions/change-secondary-contexts-selection! *state))} 
              (when (contains? selected-secondary-contexts-ids id) "!")
              title])
           secondary_contexts))]))

(defn component [_*state]
  (fn [*state]
    [:<>
     [:h2 "Search mode: " (:search_mode (:selected-context @*state))]
     [:h2 "Secondary contexts:"]
     [secondary-contexts-component *state]
     [:> ReactMarkdown
      {:children (:description (:selected-context @*state))}]]))
