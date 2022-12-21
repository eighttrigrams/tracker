(ns ui.main.lhs.context-detail
  (:require [ui.main.lhs.list-item :as list-item]))

(defn item-component [*state]
  [:ul.cards
   [list-item/component *state (:selected-context @*state)]])

(defn component [*state]
  [:<> (:description (:selected-context @*state))])
