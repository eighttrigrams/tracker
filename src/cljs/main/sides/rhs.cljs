(ns main.sides.rhs
  (:require [reagent.core :as r]
            repository))

(defn- input-component [state]
  (r/create-class 
   {:component-did-mount #(.focus (.getElementById js/document "issues-search-input"))
    :render (fn []
              [:input#issues-search-input
               {:on-change (fn [e]
                             (repository/fetch! state (.-value (.-target e))))}])}))

(defn- issues-list [state]
  [:ul
   (for [issue (:issues @state)]
     ^{:key (:id issue)}
     [:li (:title issue)])])

(defn component [_state]
  (fn [state]
    [:<>
     (when (:issues-search-active? @state)
       [input-component state])
     [issues-list state]]))
