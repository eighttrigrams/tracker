(ns et.tr.scheduling
  (:require [clojure.string :as str])
  (:import [java.time LocalDate]))

(defn add-days [date-str days]
  (str (.plusDays (LocalDate/parse date-str) days)))

(defn meets-to-create
  [today scheduled-dates existing-meet-dates]
  (let [existing (set existing-meet-dates)
        today+4 (add-days today 4)
        tomorrow (add-days today 1)
        has-meet-beyond-window? (some #(pos? (compare % today+4)) existing)
        scheduled-in-window (filterv #(and (>= (compare % today) 0)
                                           (<= (compare % today+4) 0))
                                     scheduled-dates)
        today-scheduled? (some #(= % today) scheduled-in-window)
        create-today? (and today-scheduled? (not (contains? existing today)))
        future-scheduled (filterv #(and (>= (compare % tomorrow) 0)
                                        (<= (compare % today+4) 0))
                                  scheduled-in-window)]
    (cond
      has-meet-beyond-window?
      []

      (<= (count scheduled-in-window) 1)
      (let [the-one (first scheduled-in-window)
            result (if (and the-one (not (contains? existing the-one)))
                     [the-one]
                     [])
            beyond (first (filter #(pos? (compare % today+4)) scheduled-dates))]
        (cond-> result
          (and beyond (not (contains? existing beyond))) (conj beyond)))

      :else
      (let [existing-in-future (filterv #(contains? existing %) future-scheduled)
            last-existing (when (seq existing-in-future) (last (sort existing-in-future)))
            to-fill (if last-existing
                      (filterv #(pos? (compare % last-existing)) future-scheduled)
                      future-scheduled)
            to-fill (filterv #(not (contains? existing %)) to-fill)]
        (vec (sort (cond-> to-fill
                     create-today? (conj today))))))))

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

(defn scheduled-dates-from
  [mode schedule-days-set biweekly-offset ^LocalDate start-date n]
  (loop [d start-date results [] i 0]
    (if (or (>= (count results) n) (> i 365))
      results
      (let [hit (case mode
                  "monthly" (next-monthly-date (first schedule-days-set) d)
                  "biweekly" (next-biweekly-date (first schedule-days-set) biweekly-offset d)
                  (next-weekly-date schedule-days-set d))]
        (if hit
          (recur (.plusDays (LocalDate/parse (:date hit)) 1) (conj results (:date hit)) (inc i))
          results)))))

(defn next-scheduled-date [mode schedule-days-set schedule-time biweekly-offset ^LocalDate start-date]
  (case mode
    "monthly"
    (let [dom (first schedule-days-set)]
      (next-monthly-date dom start-date))

    "biweekly"
    (let [dow (first schedule-days-set)]
      (next-biweekly-date dow biweekly-offset start-date))

    (next-weekly-date schedule-days-set start-date)))
