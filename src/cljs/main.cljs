(ns main
  (:require [reagent.core :as r]
            api
            repository
            [main.actions :as actions]
            [main.sides :as sides]))

(def original-state {:issues                []
                     :contexts              []
                     :selected-context      nil
                     :selected-issue        nil
                     ;; nil|:issues|:contexts
                     :active-search         nil})

(defn component [_keys-pressed]
  (let [*state (r/atom original-state)]
    (repository/fetch! @*state "" #(reset! *state %))
    (fn [*keys-pressed]
      (prn "keys" @*keys-pressed (:active-search @*state))
      (cond (and (not (:active-search @*state))
                 (= "KeyI" (:code @*keys-pressed)))
            (swap! *state (fn [old-state] (assoc old-state :active-search :issues)))
            (and (:active-search @*state)
                 (= "Escape" (:code @*keys-pressed)))
            (do (actions/quit-search! *state)
                (reset! *keys-pressed {}))
            (and (:selected-issue @*state)
                 (= "Escape" (:code @*keys-pressed))) 
            (do (prn "escape")
                (swap! *state (fn [old-state] (dissoc old-state :selected-issue)))
                (reset! *keys-pressed {}))
            (and (:selected-context @*state)
                 (= "Escape" (:code @*keys-pressed)))
            (do (prn "escape context")
                (swap! *state (fn [old-state] (dissoc old-state :selected-context)))))
      [:div#sides-component
       [sides/component *state]])))
 