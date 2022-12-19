(ns ui.main.lhs.list-item
  (:require repository
            [ui.actions :as actions]))

(defn component [*state context]
  [:li
   {:class    (when (= (:id (:selected-context @*state)) ;; TODO review on :id
                       (:id context)) :selected)
    :on-click #(actions/select-context! *state context)}
   [:div
    {:class (when (:important context) :important)}
    (:title context)]])
