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
                (or (:selected-issue @*state)
                    (:selected-context @*state))
                (= "KeyD" code))
           (swap! *state #(assoc % :modal :description))
           (and (not (:active-search @*state))
                (= "KeyI" code))
           (swap! *state (fn [old-state] (assoc old-state :active-search :issues)))
           (and (not (:active-search @*state))
                (= "KeyC" code))
           (swap! *state (fn [old-state] (assoc old-state :active-search :contexts)))
           (and (not (:active-search @*state))
                (:selected-context @*state)
                (= "KeyN" code))
           (swap! *state #(assoc % :modal :new-issue))
           (and (:active-search @*state)
                (= "Escape" code))
           (actions/quit-search! *state)
           (and (:selected-issue @*state)
                (= "Escape" code))
           (swap! *state (fn [old-state] (dissoc old-state :selected-issue)))
           (and (:selected-context @*state)
                (= "Escape" code))
           (actions/deselect-context! *state)))))

(defn handle-modal-keys [*state id value-fn]
  (handle-keys*
   (fn [code ctrl-pressed? e]
     (cond (= "Escape" code)
           (actions/cancel-modal! *state) 
           (and (= "KeyS" code)
                ctrl-pressed?
                (= :new-issue (:modal @*state)))
           (do (.preventDefault e)
               (actions/new-issue!
                *state
                (value-fn)))
           (and (= "KeyS" code)
                ctrl-pressed?
                (= :description (:modal @*state)))
           (do (.preventDefault e)
               (actions/save-description!
                *state
                (if (:selected-issue @*state) :issue :context)
                id
                (value-fn)))))))
