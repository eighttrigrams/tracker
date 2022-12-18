(ns ui.main.rhs
  (:require [reagent.core :as r]
            repository
            [ui.actions :as actions]))

(defn- input-component [*state]
  (r/create-class 
   {:component-did-mount #(.focus (.getElementById js/document "issues-search-input"))
    :render (fn []
              [:input#issues-search-input
               {:on-change   #(actions/search! *state (.-value (.-target %)))
                :on-key-down #(let [code (.-code %)]
                                (.stopPropagation %)
                                (when (= code "Escape")
                                  (actions/quit-search! *state)))}])}))

(defn- issues-list [*state]
  [:ul
   {:class (when (= :issues (:active-search @*state)) :active-search-list)}
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
       [:<>
        [:div.active-search-input-container [input-component *state]]
        [:div.mask.mask-active-search
         {:on-click #(actions/quit-search! *state)}]])
     [issues-list *state]]))
