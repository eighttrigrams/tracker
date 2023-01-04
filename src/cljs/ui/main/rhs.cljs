(ns ui.main.rhs
  (:require [ui.main.input :as input]
            [ui.main.rhs.issues-list-item :as issues-list-item]))

(defn- issues-list-component [*state]
  [:ul.cards
   (doall (for [issue (:issues @*state)]
            ^{:key (:id issue)}
            [issues-list-item/component *state issue]))])

(defn component [_*state]
  (fn [*state]
    [:<>
     (when (= :issues (:active-search @*state))
       [input/component *state])
     [:div.scrollable
      {:class (when (= :issues (:active-search @*state)) :search-active)}
      [issues-list-component *state]]]))
