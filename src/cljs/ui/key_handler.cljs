(ns ui.key-handler
  (:require [ui.actions :as actions]))

(defn- handle-keys* [f]
  (fn [e]
    (let [code          (.-code e)
          ctrl-pressed? (.-ctrlKey e)]
      (f code ctrl-pressed? e))))

(defn handle-keys [*state]
  (handle-keys* 
   (fn [code _ctrl-pressed? _e]
     (cond (= "Escape" code)
           (cond (:active-search @*state)
                 (actions/quit-search! *state)
                 (:selected-issue @*state)
                 (swap! *state #(dissoc % :selected-issue))
                 (:selected-context @*state)
                 (actions/deselect-context! *state))
           (and 
            (or 
             (:selected-issue @*state)
             (:selected-context @*state))
            (= "KeyD" code))
           (swap! *state #(assoc % :modal :description))
           (= "KeyI" code)
           (swap! *state #(assoc % :active-search :issues))
           (= "KeyC" code)
           (swap! *state #(assoc % :active-search :contexts))
           (and 
            (:selected-context @*state)
            (= "KeyN" code))
           (swap! *state #(assoc % :modal :new-issue))))))

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
