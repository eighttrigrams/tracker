(ns ui.actions
  (:require [ui.actions.common :refer [fetch-and-reset!]]
            api))

(defn fetch! [*state]
  (fetch-and-reset! *state @*state))

(defn quit-search! [*state]
  (fetch-and-reset! *state (dissoc @*state :active-search)))

(defn deselect-context! [*state] 
  (fetch-and-reset! *state (-> @*state
                               (dissoc :selected-context))))

(defn- select-item! [*state item key]
  (if (or (:active-search @*state) 
          (= :selected-context key))
    (fetch-and-reset! *state (-> @*state
                                 (assoc key item)
                                 (dissoc :active-search)))
    (swap! *state #(assoc % key item))))

(defn select-context! [*state context]
  (select-item! *state context :selected-context))

(defn select-issue! [*state issue]
  (select-item! *state issue :selected-issue))

(defn search! [*state value]
  (fetch-and-reset! *state @*state value))

(defn show-events! [*state]
  (fetch-and-reset! *state (-> @*state
                               (assoc :show-events? true)
                               (dissoc :selected-context))))

(defn exit-events-view! [*state]
  (fetch-and-reset! *state (assoc @*state :show-events? false)))
