(ns ui.modals.key-handler
  (:require [ui.modals.actions :as actions]
            [ui.key-handler.common :refer [handle-keys*]]))

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
