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
  ;; See below
  (swap! *state assoc :selected-context context)
  (fetch-and-reset! *state (-> @*state
                               (assoc :context-to-fetch context))))

(defn select-issue! [*state issue]
  ;; For a snappy response in the UI, set :selected-issue immediately.
  ;; The subsequent call to fetch-and-reset! then
  ;; will fetch and replace it, thereby filling in the related issues.
  (swap! *state assoc :selected-issue issue)
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
