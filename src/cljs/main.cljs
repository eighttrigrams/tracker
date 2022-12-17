(ns main
  (:require [reagent.core :as r]
            api
            repository
            [main.sides :as sides]))

(def original-state {:issues []
                     :q ""
                     :issues-search-active? false})

(defn component [_keys-pressed]
  (let [state (r/atom original-state)]
    (repository/fetch! state "")
    (fn [keys-pressed]
      (when (and (not (:issues-search-active? @state))
                 (= "KeyI" (:code @keys-pressed)))
        (swap! state (fn [old-state] (assoc old-state :issues-search-active? true))))
      [:div#sides-component
       [sides/component state]])))
