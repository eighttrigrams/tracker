(ns ui.main.rhs
  (:require repository
            [ui.actions :as actions]
            [ui.main.input :as input]))

(defn- issues-list [*state]
  [:ul
   (doall (for [issue (:issues @*state)]
            ^{:key (:id issue)}
            [:li 
             {:class    (when (= (:selected-issue @*state) issue) :selected)
              :on-click #(actions/select-issue! *state issue)}
             (:title issue)]))])

(defn component [_*state]
  (fn [*state]
    [:<>
     (when (= :issues (:active-search @*state))
       [input/component *state])
     [:div.list-component
      {:class (when (= :issues (:active-search @*state)) :search-active)}
      [issues-list *state]]]))
