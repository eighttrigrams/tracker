(ns main
  (:require [reagent.core :as r]
            api
            repository
            [main.sides :as sides]))

(def original-state {:issues                []
                     :contexts              []
                     :selected-context-id   nil
                     :selected-issue        nil
                     ;; nil|:issues|:contexts
                     :active-search         nil})

(defn component [_keys-pressed]
  (let [state (r/atom original-state)]
    (repository/fetch! state "")
    (fn [keys-pressed]
      (when (and (not (:active-search @state))
                 (= "KeyI" (:code @keys-pressed)))
        (swap! state (fn [old-state] (assoc old-state :active-search :issues))))
      [:div#sides-component
       [sides/component state]])))
