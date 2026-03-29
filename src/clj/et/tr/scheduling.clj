(ns et.tr.scheduling
  (:require [clojure.string :as str])
  (:import [java.time LocalDate]))

(defn get-schedule-time-for-day [schedule-time day-num]
  (if (and (some? schedule-time) (str/includes? schedule-time "="))
    (some (fn [pair]
            (let [[d t] (str/split pair #"=" 2)]
              (when (= d (str day-num)) t)))
          (str/split schedule-time #","))
    schedule-time))

(defn next-weekly-date [schedule-days-set ^LocalDate start-date]
  (loop [d start-date i 0]
    (when (< i 8)
      (let [day-num (.getValue (.getDayOfWeek d))]
        (if (contains? schedule-days-set (str day-num))
          {:date (str d) :day-num day-num}
          (recur (.plusDays d 1) (inc i)))))))

(defn next-monthly-date [day-of-month ^LocalDate start-date]
  (let [dom (Integer/parseInt day-of-month)
        candidate (if (>= dom (.getDayOfMonth start-date))
                    (.withDayOfMonth start-date dom)
                    (.withDayOfMonth (.plusMonths start-date 1) dom))]
    {:date (str candidate) :day-num nil}))

(defn first-monday-of-year []
  (let [year (.getYear (LocalDate/now))
        jan1 (LocalDate/of year 1 1)
        dow (.getValue (.getDayOfWeek jan1))
        offset (if (<= dow 1) (- 1 dow) (- 8 dow))]
    (.plusDays jan1 offset)))

(defn biweekly-anchor [offset-flag]
  (let [mon1 (first-monday-of-year)]
    (if (= 1 offset-flag) (.plusDays mon1 7) mon1)))

(defn next-biweekly-date [day-of-week anchor-val ^LocalDate start-date]
  (let [dow (Integer/parseInt day-of-week)
        anchor (biweekly-anchor anchor-val)]
    (loop [d start-date i 0]
      (when (< i 15)
        (let [d-dow (.getValue (.getDayOfWeek d))
              days-between (.until anchor d java.time.temporal.ChronoUnit/DAYS)
              weeks-since (quot days-between 7)]
          (if (and (= d-dow dow) (even? weeks-since))
            {:date (str d) :day-num dow}
            (recur (.plusDays d 1) (inc i))))))))

(defn is-today-scheduled? [mode schedule-days-set biweekly-offset ^LocalDate today]
  (case mode
    "monthly"
    (let [dom (first schedule-days-set)]
      (= (str (.getDayOfMonth today)) dom))

    "biweekly"
    (let [dow (first schedule-days-set)
          anchor (biweekly-anchor biweekly-offset)
          today-dow (.getValue (.getDayOfWeek today))
          days-between (.until anchor today java.time.temporal.ChronoUnit/DAYS)
          weeks-since (quot days-between 7)]
      (and (= today-dow (Integer/parseInt dow)) (even? weeks-since)))

    (contains? schedule-days-set (str (.getValue (.getDayOfWeek today))))))

(defn next-scheduled-date [mode schedule-days-set schedule-time biweekly-offset ^LocalDate start-date]
  (case mode
    "monthly"
    (let [dom (first schedule-days-set)]
      (next-monthly-date dom start-date))

    "biweekly"
    (let [dow (first schedule-days-set)]
      (next-biweekly-date dow biweekly-offset start-date))

    (next-weekly-date schedule-days-set start-date)))
