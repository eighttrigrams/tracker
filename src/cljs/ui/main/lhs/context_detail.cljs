(ns ui.main.lhs.context-detail
  (:require [ui.main.lhs.list-item :as list-item]
            ["react-markdown$default" :as ReactMarkdown]))

(defn item-component [*state]
  [:ul.cards
   [list-item/component *state (:selected-context @*state)]])

(defn component [*state]
  [:> ReactMarkdown
   {:children (:description (:selected-context @*state))}])
