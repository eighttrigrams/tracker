(ns main.sides
  (:require [main.sides.rhs :as rhs]))

(defn component [state]
  [:<>
   [:div#lhs-component "hallo"]
   [:div#rhs-component
    [:div.list-component [rhs/component state]]]])
