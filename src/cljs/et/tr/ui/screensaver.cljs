(ns et.tr.ui.screensaver
  (:require [reagent.core :as r]
            [et.tr.filters :as filters]
            [et.tr.ui.state :as state]
            [et.tr.ui.api :as api]))

(defonce *state (r/atom {:active? false
                         :current-motto nil
                         :hover? false
                         :timer-id nil
                         :listeners-installed? false}))

(def ^:private activity-events
  ["mousemove" "mousedown" "keydown" "scroll" "touchstart" "wheel"])

;; Timezone used to evaluate the day/night cutoff for motto eligibility.
;; Single point of change: swap this string, or replace with a per-user
;; setting later. The cutoff is hard-coded at 08:00 / 20:00 local-to-this-tz.
(def ^:private day-night-timezone "Europe/Lisbon")
(def ^:private day-start-hour 8)
(def ^:private day-end-hour 20)

(defn- current-hour-in-tz [tz]
  ;; Intl.DateTimeFormat with hourCycle:"h23" returns 0..23 as a string.
  (let [fmt (js/Intl.DateTimeFormat. "en-GB"
              #js {:timeZone tz :hour "2-digit" :hourCycle "h23"})]
    (js/parseInt (.format fmt (js/Date.)) 10)))

(defn- daytime-now? []
  (let [h (current-hour-in-tz day-night-timezone)]
    (and (>= h day-start-hour) (< h day-end-hour))))

(defn- matches-time-window? [motto]
  (case (or (:time_window motto) "both")
    "both" true
    "daytime" (daytime-now?)
    "nighttime" (not (daytime-now?))
    true))

(defn- screensaver-enabled? []
  (let [user (:current-user @state/*app-state)]
    (= 1 (:screensaver_enabled user))))

(defn- timeout-ms []
  (let [user (:current-user @state/*app-state)
        s (or (:screensaver_timeout_seconds user) 300)]
    (* 1000 (max 5 s))))

(defn- candidate-mottos []
  (let [mottos (or (:mottos @state/*app-state) [])
        mode (:work-private-mode @state/*app-state)
        strict? (:strict-mode @state/*app-state)]
    (filterv #(and (filters/matches-scope? % mode strict?)
                   (matches-time-window? %))
             mottos)))

(defn- pick-motto []
  (let [candidates (candidate-mottos)]
    (when (seq candidates)
      (rand-nth candidates))))

(declare arm-timer!)

(defn- ensure-mottos-loaded
  "Mottos may not be loaded yet (only fetched on settings/mottos tab init).
  This call populates :mottos for the screensaver pool. Idempotent. Re-arms
  the timer once the response arrives so an enabled screensaver still fires
  after the user opts in on the same page load."
  []
  (api/fetch-json "/api/mottos" (state/auth-headers)
    (fn [mottos]
      (swap! state/*app-state assoc :mottos mottos)
      (arm-timer!))))

(declare reset-timer!)

(defn- activate! []
  (if-let [motto (pick-motto)]
    (swap! *state assoc :active? true :current-motto motto :hover? false)
    ;; If no candidates (mottos still loading, or none match current scope)
    ;; trigger a fetch and let the response handler re-arm the timer.
    (ensure-mottos-loaded)))

(defn- clear-timer! []
  (when-let [id (:timer-id @*state)]
    (js/clearTimeout id)
    (swap! *state assoc :timer-id nil)))

(defn- arm-timer! []
  (clear-timer!)
  (when (screensaver-enabled?)
    (let [id (js/setTimeout activate! (timeout-ms))]
      (swap! *state assoc :timer-id id))))

(defn dismiss! []
  (clear-timer!)
  (swap! *state assoc :active? false :current-motto nil :hover? false)
  (arm-timer!))

(defn- handle-activity [_e]
  ;; While the overlay is up we *only* dismiss on click (see overlay's
  ;; onClick) — mousemove, keypresses and scroll should be allowed so the
  ;; user can hover to read the description.
  (when-not (:active? @*state)
    (arm-timer!)))

(defn reset-timer! []
  (arm-timer!))

(defn- install-listeners! []
  (when-not (:listeners-installed? @*state)
    (doseq [evt activity-events]
      (.addEventListener js/document evt handle-activity true))
    (swap! *state assoc :listeners-installed? true)))

(defn- uninstall-listeners! []
  (when (:listeners-installed? @*state)
    (doseq [evt activity-events]
      (.removeEventListener js/document evt handle-activity true))
    (swap! *state assoc :listeners-installed? false)))

(defn- circled-quotation []
  [:svg.motto-quote-mark {:viewBox "0 0 100 100"
                          :width "60"
                          :height "60"
                          :aria-hidden true}
   [:circle {:cx 50 :cy 50 :r 46 :fill "none"
             :stroke "currentColor" :stroke-width 3}]
   [:text {:x 50 :y 70 :text-anchor "middle"
           :font-size 70
           :font-family "Georgia, serif"
           :fill "currentColor"}
    "“"]])

(defn overlay []
  (let [{:keys [active? current-motto hover?]} @*state]
    (when (and active? current-motto)
      [:div.motto-screensaver-overlay
       {:on-click (fn [e]
                    (.stopPropagation e)
                    (dismiss!))}
       [:div.motto-screensaver-center
        {:on-mouse-enter #(swap! *state assoc :hover? true)
         :on-mouse-leave #(swap! *state assoc :hover? false)}
        [circled-quotation]
        [:div.motto-screensaver-title (:title current-motto)]
        (when (and hover? (seq (:description current-motto)))
          [:div.motto-screensaver-description-tooltip
           (:description current-motto)])]])))

(defn watcher
  "Reagent component that owns the inactivity timer and document-level
  listeners. Mounted once at the top of the app. Re-arms whenever
  the user toggles screensaver on or off."
  []
  (r/create-class
    {:component-did-mount
     (fn [_]
       (install-listeners!)
       (when (screensaver-enabled?)
         (ensure-mottos-loaded))
       (arm-timer!)
       (add-watch state/*app-state ::screensaver-pref
         (fn [_ _ old-state new-state]
           (let [old-enabled (= 1 (get-in old-state [:current-user :screensaver_enabled]))
                 new-enabled (= 1 (get-in new-state [:current-user :screensaver_enabled]))
                 old-timeout (get-in old-state [:current-user :screensaver_timeout_seconds])
                 new-timeout (get-in new-state [:current-user :screensaver_timeout_seconds])]
             (when (or (not= old-enabled new-enabled)
                       (not= old-timeout new-timeout))
               (when (and (not old-enabled) new-enabled)
                 (ensure-mottos-loaded))
               (when (and (:active? @*state) (not new-enabled))
                 (swap! *state assoc :active? false :current-motto nil))
               (arm-timer!))))))
     :component-will-unmount
     (fn [_]
       (remove-watch state/*app-state ::screensaver-pref)
       (clear-timer!)
       (uninstall-listeners!))
     :reagent-render
     (fn []
       [overlay])}))
