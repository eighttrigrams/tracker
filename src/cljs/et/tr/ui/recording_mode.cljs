(ns et.tr.ui.recording-mode
  (:require [reagent.core :as r]
            [et.tr.ui.api :as api]
            [et.tr.ui.state :as state]))

(defonce *state (r/atom {:recording? false}))

(defn- apply-response [resp]
  (swap! *state assoc :recording? (boolean (:recording resp))))

(defn toggle! []
  (api/post-json "/api/recording-mode/toggle" {} (state/auth-headers) apply-response))

(defn indicator []
  (when (:recording? @*state)
    [:div#recording-indicator
     {:title "Recording mode — REST writes are enabled"
      :style {:position "fixed"
              :top "6px"
              :left "6px"
              :z-index 10000
              :background "#c0392b"
              :color "white"
              :padding "4px 8px"
              :border-radius "4px"
              :font-size "12px"
              :font-weight "bold"
              :box-shadow "0 1px 3px rgba(0,0,0,0.3)"
              :pointer-events "none"
              :user-select "none"}}
     "\u26A0 REC"]))
