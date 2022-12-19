(ns ui.main.lhs.context-detail
  (:require [ui.main.lhs.list-item :as list-item]))

(defn component [*state]
  [:ul
   [list-item/component *state (:selected-context @*state)]])