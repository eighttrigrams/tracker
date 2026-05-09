(ns et.tr.ui.state.events
  (:require [et.tr.ui.api :as api]))

(defn fetch-events [app-state auth-headers]
  (api/fetch-json-with-error "/api/events" (auth-headers)
    (fn [resp]
      (swap! app-state assoc :events (or (:events resp) [])))
    (fn [_]
      (swap! app-state assoc :events []))))
