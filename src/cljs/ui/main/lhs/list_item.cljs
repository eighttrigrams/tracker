(ns ui.main.lhs.list-item
  (:require repository
            [ui.actions :as actions]))

(defn component [*state context]
  [:li
   {:class    (when (= (:selected-context @*state) context) :selected)
    :on-click #(actions/select-context! *state context)}
   (:title context)])
