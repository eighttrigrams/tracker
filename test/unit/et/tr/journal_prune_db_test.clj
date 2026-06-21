(ns et.tr.journal-prune-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.tr.db :as db]
            [et.tr.db.journal :as db.journal]
            [et.tr.db.journal-entry :as db.journal-entry]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]])
  (:import [java.time LocalDate DayOfWeek]
           [java.time.temporal TemporalAdjusters]))

(use-fixtures :each with-in-memory-db)

(def ^:private old-ts "2020-01-01 00:00:00")

(defn- iso [^LocalDate d] (str d))

(defn- today [] (LocalDate/now))
(defn- days-ago [n] (.minusDays (today) n))
(defn- days-ahead [n] (.plusDays (today) n))
(defn- monday-this [] (.with (today) (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY)))
(defn- monday-last [] (.minusWeeks (monday-this) 1))

(defn- insert-entry! [journal-id entry-date description created-at]
  (:id (jdbc/execute-one! (db/get-conn *ds*)
         (sql/format {:insert-into :journal_entries
                      :values [{:title "Entry"
                                :description description
                                :sort_order 1.0
                                :scope "both"
                                :user_id *user-id*
                                :journal_id journal-id
                                :entry_date entry-date
                                :created_at created-at
                                :modified_at created-at}]
                      :returning [:id]})
         db/jdbc-opts)))

(defn- exists? [id]
  (some? (jdbc/execute-one! (db/get-conn *ds*)
           (sql/format {:select [:id] :from [:journal_entries] :where [:= :id id]})
           db/jdbc-opts)))

(deftest prune-daily-keeps-today-and-yesterday
  (let [j (db.journal/add-journal *ds* *user-id* "Daily" "both" "daily")
        today-id (insert-entry! (:id j) (iso (today)) "" old-ts)
        yesterday-id (insert-entry! (:id j) (iso (days-ago 1)) "" old-ts)
        older-id (insert-entry! (:id j) (iso (days-ago 2)) "" old-ts)]
    (db.journal-entry/prune-empty-entries *ds* *user-id*)
    (testing "today and yesterday are kept"
      (is (exists? today-id))
      (is (exists? yesterday-id)))
    (testing "older empty daily entry is deleted"
      (is (not (exists? older-id))))))

(deftest prune-weekly-keeps-this-and-last-week
  (let [j (db.journal/add-journal *ds* *user-id* "Weekly" "both" "weekly")
        this-week-id (insert-entry! (:id j) (iso (monday-this)) "" old-ts)
        last-week-id (insert-entry! (:id j) (iso (monday-last)) "" old-ts)
        older-id (insert-entry! (:id j) (iso (.minusDays (monday-last) 1)) "" old-ts)]
    (db.journal-entry/prune-empty-entries *ds* *user-id*)
    (testing "this week and last week are kept"
      (is (exists? this-week-id))
      (is (exists? last-week-id)))
    (testing "older empty weekly entry is deleted"
      (is (not (exists? older-id))))))

(deftest prune-never-deletes-future-dated
  (let [j (db.journal/add-journal *ds* *user-id* "Daily" "both" "daily")
        future-id (insert-entry! (:id j) (iso (days-ahead 5)) "" old-ts)]
    (db.journal-entry/prune-empty-entries *ds* *user-id*)
    (is (exists? future-id))))

(deftest prune-respects-24h-grace
  (let [j (db.journal/add-journal *ds* *user-id* "Daily" "both" "daily")
        fresh-id (insert-entry! (:id j) (iso (days-ago 10)) "" [:raw "datetime('now')"])]
    (db.journal-entry/prune-empty-entries *ds* *user-id*)
    (testing "an entry created within the last 24h is spared even though old-dated"
      (is (exists? fresh-id)))))

(deftest prune-keeps-entries-with-description
  (let [j (db.journal/add-journal *ds* *user-id* "Daily" "both" "daily")
        kept-id (insert-entry! (:id j) (iso (days-ago 10)) "some notes" old-ts)]
    (db.journal-entry/prune-empty-entries *ds* *user-id*)
    (is (exists? kept-id))))

(deftest prune-deletes-empty-string-and-null-descriptions
  (let [j (db.journal/add-journal *ds* *user-id* "Daily" "both" "daily")
        empty-id (insert-entry! (:id j) (iso (days-ago 10)) "" old-ts)
        null-id (insert-entry! (:id j) (iso (days-ago 10)) nil old-ts)]
    (db.journal-entry/prune-empty-entries *ds* *user-id*)
    (testing "both empty-string and NULL descriptions are deleted"
      (is (not (exists? empty-id)))
      (is (not (exists? null-id))))))

(deftest prune-leaves-standalone-entries-untouched
  (let [j (db.journal/add-journal *ds* *user-id* "Daily" "both" "daily")
        no-journal-id (insert-entry! nil (iso (days-ago 10)) "" old-ts)
        no-date-id (insert-entry! (:id j) nil "" old-ts)]
    (db.journal-entry/prune-empty-entries *ds* *user-id*)
    (testing "entries with NULL journal_id or NULL entry_date are kept"
      (is (exists? no-journal-id))
      (is (exists? no-date-id)))))
