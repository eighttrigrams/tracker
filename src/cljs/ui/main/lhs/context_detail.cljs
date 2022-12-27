(ns ui.main.lhs.context-detail
  (:require [ui.main.lhs.list-item :as list-item]
            ["react-markdown$default" :as ReactMarkdown]))

(defn item-component [*state]
  [:ul.cards
   [list-item/component *state (:selected-context @*state)]])

(defn component [*state]
  [:<>
   [:h2 "Search mode: " (:search_mode (:selected-context @*state))]
   [:h2 "Secondary contexts:"]
   [:ul
    (doall (map (fn [[id title]]
                  [:li
                   {:key id} title]) 
                (:secondary_contexts (:selected-context @*state))))]
   [:> ReactMarkdown
    {:children (:description (:selected-context @*state))}]])
