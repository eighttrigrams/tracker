(ns et.tr.ui.save-flash
  (:require [reagent.core :as r]))

;; Kept in step with the .save-flash-mark animation duration in modal.css.
(def ^:private visible-ms 1500)

(defonce ^:private *state (r/atom {:visible? false :flashes 0 :timer nil}))

(defn flash! []
  (when-let [timer (:timer @*state)]
    (js/clearTimeout timer))
  (let [timer (js/setTimeout #(swap! *state assoc :visible? false :timer nil) visible-ms)]
    (swap! *state #(-> %
                       (assoc :visible? true :timer timer)
                       (update :flashes inc)))))

(defn indicator []
  (let [{:keys [visible? flashes]} @*state]
    (when visible?
      [:div#save-flash
       ;; The flash counter as :key remounts the mark, which is what restarts
       ;; the CSS animation when a save flashes while one is still on screen.
       [:span.save-flash-mark {:key flashes} "✓"]])))
