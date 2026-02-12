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

(defn- month-number->translation-key [month-num]
  (case month-num
    0 :date/january
    1 :date/february
    2 :date/march
    3 :date/april
    4 :date/may
    5 :date/june
    6 :date/july
    7 :date/august
    8 :date/september
    9 :date/october
    10 :date/november
    11 :date/december
    nil))

(defn format-date-localized [date-str]
  (when date-str
    (let [d (js/Date. (str date-str "T12:00:00"))
          day (.getDate d)
          month-key (month-number->translation-key (.getMonth d))
          year (.getFullYear d)]
      (str day " " (i18n/t month-key) ", " year))))

(defn format-datetime-localized [datetime-str]
  (when datetime-str
    (if-let [space-idx (.indexOf datetime-str " ")]
      (if (> space-idx 0)
        (let [date-part (.substring datetime-str 0 space-idx)
              time-part (.substring datetime-str (inc space-idx))]
          (str (format-date-localized date-part) ", " time-part))
        (format-date-localized datetime-str))
      (format-date-localized datetime-str))))

(defn format-date-with-day [date-str]
  (when date-str
    (if-let [day-key (day-number->translation-key (day-of-week date-str))]
      (str (format-date-localized date-str) ", " (i18n/t day-key))
      (format-date-localized date-str))))

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
    (str (i18n/t :today/today) ", " (i18n/t day-key) ", " (format-date-localized today))))

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
