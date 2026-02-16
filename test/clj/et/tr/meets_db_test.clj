(ns et.tr.meets-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db :as db]
            [et.tr.test-helpers :refer [*ds* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(deftest add-meet-auto-assigns-date-and-time-test
  (testing "new meet gets start_date and start_time auto-assigned"
    (let [meet (db/add-meet *ds* nil "Standup")]
      (is (some? (:start_date meet)))
      (is (re-matches #"\d{4}-\d{2}-\d{2}" (:start_date meet)))
      (is (some? (:start_time meet)))
      (is (re-matches #"\d{2}:\d{2}" (:start_time meet))))))

(deftest list-meets-upcoming-mode-test
  (testing "upcoming mode returns meets with start_date >= today"
    (let [meet1 (db/add-meet *ds* nil "Future meet")
          meet2 (db/add-meet *ds* nil "Past meet")]
      (db/set-meet-start-date *ds* nil (:id meet1) "2099-01-01")
      (db/set-meet-start-date *ds* nil (:id meet2) "2020-01-01")
      (let [meets (db/list-meets *ds* nil {:sort-mode :upcoming})]
        (is (= 1 (count meets)))
        (is (= "Future meet" (:title (first meets))))))))

(deftest list-meets-past-mode-test
  (testing "past mode returns meets with start_date < today"
    (let [meet1 (db/add-meet *ds* nil "Future meet")
          meet2 (db/add-meet *ds* nil "Past meet")]
      (db/set-meet-start-date *ds* nil (:id meet1) "2099-01-01")
      (db/set-meet-start-date *ds* nil (:id meet2) "2020-01-01")
      (let [meets (db/list-meets *ds* nil {:sort-mode :past})]
        (is (= 1 (count meets)))
        (is (= "Past meet" (:title (first meets))))))))

(deftest list-meets-upcoming-order-test
  (testing "upcoming mode orders by start_date ASC, start_time ASC"
    (let [m1 (db/add-meet *ds* nil "Later")
          m2 (db/add-meet *ds* nil "Earlier")
          m3 (db/add-meet *ds* nil "Same day, later time")]
      (db/set-meet-start-date *ds* nil (:id m1) "2099-03-01")
      (db/set-meet-start-time *ds* nil (:id m1) "10:00")
      (db/set-meet-start-date *ds* nil (:id m2) "2099-01-01")
      (db/set-meet-start-time *ds* nil (:id m2) "09:00")
      (db/set-meet-start-date *ds* nil (:id m3) "2099-01-01")
      (db/set-meet-start-time *ds* nil (:id m3) "14:00")
      (is (= ["Earlier" "Same day, later time" "Later"]
             (mapv :title (db/list-meets *ds* nil {:sort-mode :upcoming})))))))

(deftest list-meets-past-order-test
  (testing "past mode orders by start_date DESC, start_time DESC"
    (let [m1 (db/add-meet *ds* nil "Older")
          m2 (db/add-meet *ds* nil "More recent")
          m3 (db/add-meet *ds* nil "Same day, earlier time")]
      (db/set-meet-start-date *ds* nil (:id m1) "2020-01-01")
      (db/set-meet-start-time *ds* nil (:id m1) "10:00")
      (db/set-meet-start-date *ds* nil (:id m2) "2020-06-01")
      (db/set-meet-start-time *ds* nil (:id m2) "14:00")
      (db/set-meet-start-date *ds* nil (:id m3) "2020-06-01")
      (db/set-meet-start-time *ds* nil (:id m3) "09:00")
      (is (= ["More recent" "Same day, earlier time" "Older"]
             (mapv :title (db/list-meets *ds* nil {:sort-mode :past})))))))

(deftest set-meet-start-date-test
  (testing "sets start date and returns updated fields"
    (let [meet (db/add-meet *ds* nil "Meet")
          result (db/set-meet-start-date *ds* nil (:id meet) "2026-05-15")]
      (is (= "2026-05-15" (:start_date result)))
      (is (some? (:modified_at result))))))

(deftest set-meet-start-time-test
  (testing "sets start time and returns updated fields"
    (let [meet (db/add-meet *ds* nil "Meet")
          result (db/set-meet-start-time *ds* nil (:id meet) "14:30")]
      (is (= "14:30" (:start_time result)))
      (is (some? (:modified_at result)))))

  (testing "clears start time when empty string"
    (let [meet (db/add-meet *ds* nil "Meet2")]
      (db/set-meet-start-time *ds* nil (:id meet) "09:00")
      (is (nil? (:start_time (db/set-meet-start-time *ds* nil (:id meet) "")))))))

(deftest list-meets-exclude-by-place-test
  (let [place (db/add-place *ds* nil "Office")
        m1 (db/add-meet *ds* nil "At office")
        m2 (db/add-meet *ds* nil "Remote")]
    (db/set-meet-start-date *ds* nil (:id m1) "2099-01-01")
    (db/set-meet-start-date *ds* nil (:id m2) "2099-01-01")
    (db/categorize-meet *ds* nil (:id m1) "place" (:id place))
    (let [meets (db/list-meets *ds* nil {:excluded-places ["Office"]})]
      (is (= 1 (count meets)))
      (is (= "Remote" (:title (first meets)))))))

(deftest list-meets-exclude-by-project-test
  (let [project (db/add-project *ds* nil "Alpha")
        m1 (db/add-meet *ds* nil "Alpha standup")
        m2 (db/add-meet *ds* nil "General")]
    (db/set-meet-start-date *ds* nil (:id m1) "2099-01-01")
    (db/set-meet-start-date *ds* nil (:id m2) "2099-01-01")
    (db/categorize-meet *ds* nil (:id m1) "project" (:id project))
    (let [meets (db/list-meets *ds* nil {:excluded-projects ["Alpha"]})]
      (is (= ["General"] (mapv :title meets))))))
