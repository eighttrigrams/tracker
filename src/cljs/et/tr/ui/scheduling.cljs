(ns et.tr.ui.scheduling
  (:require [clojure.string]))

(defn js-day-to-iso-day [js-day]
  (if (= js-day 0) 7 js-day))

(defn- per-day-time? [s]
  (and (some? s) (clojure.string/includes? s "=")))

(defn get-schedule-time-for-day [schedule-time day-num]
  (if (per-day-time? schedule-time)
    (let [pairs (clojure.string/split schedule-time #",")]
      (some (fn [pair]
              (let [[d t] (clojure.string/split pair #"=" 2)]
                (when (= d (str day-num)) t)))
            pairs))
    schedule-time))

(defn format-js-date [js-d]
  (let [y (.getFullYear js-d)
        m (.padStart (str (+ 1 (.getMonth js-d))) 2 "0")
        day (.padStart (str (.getDate js-d)) 2 "0")]
    (str y "-" m "-" day)))

(defn advance-day [date-str]
  (let [js-d (js/Date. (str date-str "T00:00:00"))]
    (.setDate js-d (+ (.getDate js-d) 1))
    (format-js-date js-d)))

(defn next-scheduled-date-from [schedule-days-set start-date]
  (loop [d start-date i 0]
    (when (< i 8)
      (let [js-d (js/Date. (str d "T00:00:00"))
            day-num (js-day-to-iso-day (.getDay js-d))]
        (if (contains? schedule-days-set (str day-num))
          {:date d :day-num day-num}
          (recur (advance-day d) (inc i)))))))

(defn- next-monthly-date-from [day-of-month start-date]
  (let [dom (js/parseInt day-of-month)
        js-d (js/Date. (str start-date "T00:00:00"))
        current-dom (.getDate js-d)]
    (if (>= dom current-dom)
      (do (.setDate js-d dom)
          {:date (format-js-date js-d) :day-num nil})
      (do (.setMonth js-d (+ (.getMonth js-d) 1))
          (.setDate js-d dom)
          {:date (format-js-date js-d) :day-num nil}))))

(defn- first-monday-of-year []
  (let [y (.getFullYear (js/Date.))
        jan1 (js/Date. y 0 1)
        dow (.getDay jan1)
        offset (if (<= dow 1) (- 1 dow) (- 8 dow))]
    (js/Date. y 0 (+ 1 offset))))

(defn- biweekly-anchor [offset-flag]
  (let [mon1 (first-monday-of-year)]
    (if (= 1 offset-flag)
      (js/Date. (.getFullYear mon1) (.getMonth mon1) (+ (.getDate mon1) 7))
      mon1)))

(defn next-biweekly-date-from [day-of-week offset-flag start-date]
  (let [dow (js/parseInt day-of-week)
        anchor-js (biweekly-anchor offset-flag)
        anchor-time (.getTime anchor-js)]
    (loop [d start-date i 0]
      (when (< i 15)
        (let [js-d (js/Date. (str d "T00:00:00"))
              d-dow (js-day-to-iso-day (.getDay js-d))
              days-between (js/Math.round (/ (- (.getTime js-d) anchor-time) 86400000))
              weeks-since (js/Math.floor (/ days-between 7))]
          (if (and (= d-dow dow) (even? weeks-since))
            {:date d :day-num dow}
            (recur (advance-day d) (inc i))))))))

(defn today-str []
  (format-js-date (js/Date.)))

(defn tomorrow-str []
  (let [now (js/Date.)
        tomorrow (js/Date. (.getTime now))]
    (.setDate tomorrow (+ (.getDate tomorrow) 1))
    (format-js-date tomorrow)))

(defn- is-today-scheduled? [mode schedule-days-set biweekly-offset today-str]
  (let [today-js (js/Date. (str today-str "T00:00:00"))]
    (case mode
      "monthly"
      (let [dom (first schedule-days-set)]
        (= (str (.getDate today-js)) dom))

      "biweekly"
      (let [dow (first schedule-days-set)
            today-dow (js-day-to-iso-day (.getDay today-js))
            anchor-js (biweekly-anchor biweekly-offset)
            days-between (js/Math.round (/ (- (.getTime today-js) (.getTime anchor-js)) 86400000))
            weeks-since (js/Math.floor (/ days-between 7))]
        (and (= today-dow (js/parseInt dow)) (even? weeks-since)))

      (contains? schedule-days-set (str (js-day-to-iso-day (.getDay today-js)))))))

(defn next-scheduled-date-for-mode [mode schedule-days-set schedule-time biweekly-offset start-date]
  (case mode
    "monthly"  (next-monthly-date-from (first schedule-days-set) start-date)
    "biweekly" (next-biweekly-date-from (first schedule-days-set) biweekly-offset start-date)
    (next-scheduled-date-from schedule-days-set start-date)))

(defn next-scheduled-action [entity {:keys [has-today-key has-future-key]}]
  (let [has-today (get entity has-today-key)
        has-future (get entity has-future-key)
        {:keys [schedule_days schedule_time schedule_mode biweekly_offset task_type]} entity
        mode (or schedule_mode "weekly")
        schedule-days-set (if (or (nil? schedule_days) (= schedule_days ""))
                            #{}
                            (set (clojure.string/split schedule_days #",")))
        today (today-str)]
    (if (= task_type "today")
      (cond
        has-today {:action :none}
        (is-today-scheduled? mode schedule-days-set biweekly_offset today)
        {:action :create-today :date today :time nil}
        :else {:action :none})
      (cond
        has-future
        {:action :none}

        has-today
        (when-let [{:keys [date day-num]} (next-scheduled-date-for-mode mode schedule-days-set schedule_time biweekly_offset (tomorrow-str))]
          {:action :create-next :date date :time (if day-num (get-schedule-time-for-day schedule_time day-num) schedule_time)})

        (is-today-scheduled? mode schedule-days-set biweekly_offset today)
        (let [today-js (js/Date. (str today "T00:00:00"))
              today-day-num (js-day-to-iso-day (.getDay today-js))]
          {:action :create-today :date today :time (get-schedule-time-for-day schedule_time today-day-num)})

        :else
        (when-let [{:keys [date day-num]} (next-scheduled-date-for-mode mode schedule-days-set schedule_time biweekly_offset (tomorrow-str))]
          {:action :create-next :date date :time (if day-num (get-schedule-time-for-day schedule_time day-num) schedule_time)})))))
