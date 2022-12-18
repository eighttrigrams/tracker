(ns ui.key-handler
  (:require [ui.actions :as actions]))

(defn handle-keys [*state]
  (fn [e]
    (let [code           (.-code e)
          _ctrl-pressed? (.-ctrlKey e)]
      (cond (and (not (:active-search @*state))
                 (= "KeyI" code))
            (swap! *state (fn [old-state] (assoc old-state :active-search :issues)))
            (and (:active-search @*state)
                 (= "Escape" code))
            (actions/quit-search! *state)
            (and (:selected-issue @*state)
                 (= "Escape" code))
            (do (prn "escape")
                (swap! *state (fn [old-state] (dissoc old-state :selected-issue))))
            (and (:selected-context @*state)
                 (= "Escape" code))
            (do (prn "escape context")
                (swap! *state (fn [old-state] (dissoc old-state :selected-context))))))))
