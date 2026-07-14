(ns et.tr.clock
  "Single source of \"today\" for date-window filtering and date-defaulting
  writes. In production the real system clock is used and the emitted SQL is
  byte-identical to the original date('now')/datetime('now') expressions. When
  the TRACKER_FAKE_TODAY (yyyy-MM-dd) env var is set — dev/e2e only — the clock
  is pinned to that date so date-sensitive tests are weekday-independent. The
  SQLite side shifts by a whole number of days (rather than inlining a literal)
  so localtime handling and sub-day monotonic ordering of timestamps are
  preserved."
  (:import [java.time LocalDate]
           [java.time.temporal ChronoUnit]))

(defn- env-fake-today []
  (let [v (or (System/getenv "TRACKER_FAKE_TODAY")
              (System/getProperty "tracker.fake-today"))]
    (when (and v (re-matches #"\d{4}-\d{2}-\d{2}" v))
      (LocalDate/parse v))))

(defn today
  "Today as a LocalDate — the pinned fake date when set, otherwise the real
  system date."
  ^LocalDate []
  (or (env-fake-today) (LocalDate/now)))

(defn today-str []
  (str (today)))

(defn- fake-day-shift
  "SQLite datetime modifier (e.g. \"+2 days\") moving the real clock onto the
  pinned fake date, or nil when no fake date is set."
  []
  (when-let [fake (env-fake-today)]
    (format "%+d days" (.until (LocalDate/now) fake ChronoUnit/DAYS))))

(defn- sql-date-expr [fn-name mods]
  (let [shift (fake-day-shift)
        all (->> (concat (when (= fn-name "date") ["localtime"])
                         (when shift [shift])
                         mods)
                 (map #(str ",'" % "'"))
                 (apply str))]
    [:raw (str fn-name "('now'" all ")")]))

(defn sql-today
  "HoneySQL expr for today's date, i.e. date('now','localtime'[,mods...]),
  shifted onto the fake date when pinned."
  [& mods]
  (sql-date-expr "date" mods))

(defn sql-now
  "HoneySQL expr for the current timestamp, i.e. datetime('now'[,mods...]),
  shifted onto the fake date when pinned."
  [& mods]
  (sql-date-expr "datetime" mods))
