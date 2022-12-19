(ns ui.key-handler
  (:require [ui.actions :as actions]))

(defn- handle-keys* [f]
  (fn [e]
    (let [code           (.-code e)
          ctrl-pressed? (.-ctrlKey e)]
      (f code ctrl-pressed? e))))

(defn handle-keys [*state]
  (handle-keys* 
   (fn [code _ctrl-pressed? _e]
     (cond (and (not (:active-search @*state))
                (= "KeyD" code))
           (swap! *state #(assoc % :modal :description))
           (and (not (:active-search @*state))
                (= "KeyI" code))
           (swap! *state (fn [old-state] (assoc old-state :active-search :issues)))
           (and (not (:active-search @*state))
                (= "KeyC" code))
           (swap! *state (fn [old-state] (assoc old-state :active-search :contexts)))
           (and (:active-search @*state)
                (= "Escape" code))
           (actions/quit-search! *state)
           (and (:selected-issue @*state)
                (= "Escape" code))
           (swap! *state (fn [old-state] (dissoc old-state :selected-issue)))
           (and (:selected-context @*state)
                (= "Escape" code))
           (actions/deselect-context! *state)))))

(defn handle-modal-keys [*state]
  (handle-keys*
   (fn [code ctrl-pressed? e]
     (cond (= "Escape" code)
           (actions/cancel-modal! *state)
           (and (= "KeyS" code)
                ctrl-pressed?)
           (do (.preventDefault e)
             (prn "yesyo!"))))))
