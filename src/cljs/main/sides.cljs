(ns main.sides
  (:require [main.sides.lhs :as lhs]
            [main.sides.rhs :as rhs]))

(defn component [state*]
  [:<>
   [:div#lhs-component.list-component [lhs/component state*]] 
   [:div#rhs-component.list-component [rhs/component state*]]])
