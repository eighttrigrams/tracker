(ns et.tr.server.week-window
  (:require [et.tr.clock :as clock])
  (:import [java.time DayOfWeek]
           [java.time.temporal TemporalAdjusters]))

(defn parse-week-param [v default]
  (if (and v (re-matches #"\d+" v))
    (Integer/parseInt v)
    default))

(defn week-window [week-offset week-limit direction]
  (let [current-monday (.with (clock/today) (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY))]
    (case direction
      :forward {:date-from (str (.plusDays current-monday (* 7 week-offset)))
                :date-to (str (.plusDays current-monday (* 7 (+ week-offset week-limit))))}
      (let [next-monday (.plusDays current-monday 7)]
        {:date-from (str (.minusDays next-monday (* 7 (+ week-offset week-limit))))
         :date-to (str (.minusDays next-monday (* 7 week-offset)))}))))
