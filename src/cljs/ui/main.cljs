(ns ui.main
  (:require repository
            [ui.main.lhs :as lhs]
            [ui.main.rhs :as rhs]))

(defn component [*state]
  (repository/fetch! @*state "" #(reset! *state %))
  (fn [*state]
    [:div#sides-container
     [:div#lhs-component.list-component [lhs/component *state]]
     [:div#rhs-component.list-component [rhs/component *state]]]))
