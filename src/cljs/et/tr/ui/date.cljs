(ns et.tr.ui.date
  (:require [et.tr.i18n :as i18n]))

(defn today-str []
  (.substring (.toISOString (js/Date.)) 0 10))

(defn add-days [date-str days]
  (let [d (js/Date. (str date-str "T12:00:00"))]
    (.setDate d (+ (.getDate d) days))
    (.substring (.toISOString d) 0 10)))

(defn day-of-week [date-str]
  (let [d (js/Date. (str date-str "T12:00:00"))]
    (.getDay d)))

(defn- day-number->translation-key [day-num]
  (case day-num
    0 :date/sunday
    1 :date/monday
    2 :date/tuesday
    3 :date/wednesday
    4 :date/thursday
    5 :date/friday
    6 :date/saturday
    nil))

(defn format-date-with-day [date-str]
  (when date-str
    (if-let [day-key (day-number->translation-key (day-of-week date-str))]
      (str date-str ", " (i18n/t day-key))
      date-str)))

(defn get-day-name [date-str]
  (when date-str
    (when-let [day-key (day-number->translation-key (day-of-week date-str))]
      (i18n/t day-key))))

(defn within-days? [date-str days]
  (when date-str
    (let [today (today-str)
          end-date (add-days today days)]
      (and (> date-str today)
           (<= date-str end-date)))))

(defn today-formatted []
  (let [today (today-str)
        day-key (day-number->translation-key (day-of-week today))]
    (str (i18n/t :today/today) ", " (i18n/t day-key) ", " today)))

(def horizon-order [:three-days :week :month :three-months :year :eighteen-months])

(defn horizon-end-date [horizon]
  (let [today (today-str)]
    (case horizon
      :three-days (add-days today 3)
      :week (add-days today 7)
      :month (add-days today 30)
      :three-months (add-days today 90)
      :year (add-days today 365)
      :eighteen-months (add-days today 548)
      (add-days today 7))))
