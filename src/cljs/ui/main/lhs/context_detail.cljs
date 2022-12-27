(ns ui.main.lhs.context-detail
  (:require [ui.main.lhs.list-item :as list-item]
            ["react-markdown$default" :as ReactMarkdown]))

(defn item-component [*state]
  [:ul.cards
   [list-item/component *state (:selected-context @*state)]])

(defn component [*state]
  [:<>
   [:ul
    (doall (map (fn [[id title]]
                  [:li
                   {:key id} title]) 
                (:secondary_contexts (:selected-context @*state))))]
   [:> ReactMarkdown
    {:children (:description (:selected-context @*state))}]])
