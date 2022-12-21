(ns ui.main.rhs
  (:require [ui.actions :as actions]
            [ui.main.input :as input]))


(defn- issues-list-item-component [*state issue]
  [:li.card
   {:class     (when (= (:id (:selected-issue @*state)) ;; TODO review on :id
                        (:id issue)) :selected)
    :on-click #(actions/select-issue! *state issue)}
   [:div
    {:class (when (:important issue) :important)}
    (:title issue)]])

(defn- issues-list-component [*state]
  [:ul.cards
   (doall (for [issue (:issues @*state)]
            ^{:key (:id issue)}
            [issues-list-item-component *state issue]))])

(defn component [_*state]
  (fn [*state]
    [:<>
     (when (= :issues (:active-search @*state))
       [input/component *state])
     [:div.scrollable
      {:class (when (= :issues (:active-search @*state)) :search-active)}
      [issues-list-component *state]]]))
