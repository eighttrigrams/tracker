(ns ui.key-handler
  (:require [ui.actions :as actions]
            [ui.key-handler.common :refer [handle-keys*]]))

(defn handle-keys [*state]
  (handle-keys* 
   (fn [code _ctrl-pressed? _e]
     (cond (= "Escape" code)
           (cond (:active-search @*state)
                 (actions/quit-search! *state)
                 (:show-events? @*state)
                 (actions/exit-events-view! *state)
                 (:selected-issue @*state)
                 (swap! *state #(dissoc % :selected-issue))
                 (:selected-context @*state)
                 (actions/deselect-context! *state))
           (not (:active-search @*state))
           (cond
             (= "KeyV" code)
             (actions/show-events! *state)
             (and 
              (or 
               (:selected-issue @*state)
               (:selected-context @*state))
              (= "KeyD" code))
             (swap! *state #(assoc % :modal :description))
             (and (:selected-issue @*state)
                  (= "KeyE" code))
             (swap! *state #(assoc % :modal :edit-issue))
             (and (= "KeyI" code) (not (:show-events? @*state)))
             (swap! *state #(assoc % :active-search :issues))
             (and (= "KeyC" code) (not (:show-events? @*state)))
             (swap! *state #(assoc % :active-search :contexts))
             (and
              (:selected-context @*state)
              (= "KeyN" code))
             (swap! *state #(assoc % :modal :new-issue)))))))
