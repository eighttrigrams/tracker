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

(defn select-context! [*state context]
  (fetch-and-reset! *state (-> @*state
                               (assoc :selected-context context)
                               (dissoc :active-search))))

(defn select-issue! [*state issue]
  ;; TODO Set :selected-issue immediately, for a snappy response in the UI.
  ;;      Use issue argument for that. The subsequent call to fetch-and-reset! then
  ;;      will fetch and replace that, and thereby fetch the connected-issues.
  (fetch-and-reset! *state (-> @*state
                               (assoc :issue-to-fetch issue))))

(defn search! [*state value]
  (fetch-and-reset! *state @*state value))

(defn show-events! [*state]
  (fetch-and-reset! *state (-> @*state
                               (assoc :show-events? true)
                               (dissoc :selected-context))))

(defn exit-events-view! [*state]
  (fetch-and-reset! *state (assoc @*state :show-events? false)))
