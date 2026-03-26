(ns et.tr.meets-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db.meet :as db.meet]
            [et.tr.db.category :as db.category]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(deftest add-meet-auto-assigns-date-and-time-test
  (testing "new meet gets start_date and start_time auto-assigned"
    (let [meet (db.meet/add-meet *ds* *user-id* "Standup")]
      (is (some? (:start_date meet)))
      (is (re-matches #"\d{4}-\d{2}-\d{2}" (:start_date meet)))
      (is (some? (:start_time meet)))
      (is (re-matches #"\d{2}:\d{2}" (:start_time meet))))))

(deftest list-meets-upcoming-mode-test
  (testing "upcoming mode returns meets with start_date >= today"
    (let [meet1 (db.meet/add-meet *ds* *user-id* "Future meet")
          meet2 (db.meet/add-meet *ds* *user-id* "Past meet")]
      (db.meet/set-meet-start-date *ds* *user-id* (:id meet1) "2099-01-01")
      (db.meet/set-meet-start-date *ds* *user-id* (:id meet2) "2020-01-01")
      (let [meets (db.meet/list-meets *ds* *user-id* {:sort-mode :upcoming})]
        (is (= 1 (count meets)))
        (is (= "Future meet" (:title (first meets))))))))

(deftest list-meets-past-mode-test
  (testing "past mode returns meets with start_date < today"
    (let [meet1 (db.meet/add-meet *ds* *user-id* "Future meet")
          meet2 (db.meet/add-meet *ds* *user-id* "Past meet")]
      (db.meet/set-meet-start-date *ds* *user-id* (:id meet1) "2099-01-01")
      (db.meet/set-meet-start-date *ds* *user-id* (:id meet2) "2020-01-01")
      (let [meets (db.meet/list-meets *ds* *user-id* {:sort-mode :past})]
        (is (= 1 (count meets)))
        (is (= "Past meet" (:title (first meets))))))))

(deftest list-meets-upcoming-order-test
  (testing "upcoming mode orders by start_date ASC, start_time ASC"
    (let [m1 (db.meet/add-meet *ds* *user-id* "Later")
          m2 (db.meet/add-meet *ds* *user-id* "Earlier")
          m3 (db.meet/add-meet *ds* *user-id* "Same day, later time")]
      (db.meet/set-meet-start-date *ds* *user-id* (:id m1) "2099-03-01")
      (db.meet/set-meet-start-time *ds* *user-id* (:id m1) "10:00")
      (db.meet/set-meet-start-date *ds* *user-id* (:id m2) "2099-01-01")
      (db.meet/set-meet-start-time *ds* *user-id* (:id m2) "09:00")
      (db.meet/set-meet-start-date *ds* *user-id* (:id m3) "2099-01-01")
      (db.meet/set-meet-start-time *ds* *user-id* (:id m3) "14:00")
      (is (= ["Earlier" "Same day, later time" "Later"]
             (mapv :title (db.meet/list-meets *ds* *user-id* {:sort-mode :upcoming})))))))

(deftest list-meets-past-order-test
  (testing "past mode orders by start_date DESC, start_time DESC"
    (let [m1 (db.meet/add-meet *ds* *user-id* "Older")
          m2 (db.meet/add-meet *ds* *user-id* "More recent")
          m3 (db.meet/add-meet *ds* *user-id* "Same day, earlier time")]
      (db.meet/set-meet-start-date *ds* *user-id* (:id m1) "2020-01-01")
      (db.meet/set-meet-start-time *ds* *user-id* (:id m1) "10:00")
      (db.meet/set-meet-start-date *ds* *user-id* (:id m2) "2020-06-01")
      (db.meet/set-meet-start-time *ds* *user-id* (:id m2) "14:00")
      (db.meet/set-meet-start-date *ds* *user-id* (:id m3) "2020-06-01")
      (db.meet/set-meet-start-time *ds* *user-id* (:id m3) "09:00")
      (is (= ["More recent" "Same day, earlier time" "Older"]
             (mapv :title (db.meet/list-meets *ds* *user-id* {:sort-mode :past})))))))

(deftest set-meet-start-date-test
  (testing "sets start date and returns updated fields"
    (let [meet (db.meet/add-meet *ds* *user-id* "Meet")
          result (db.meet/set-meet-start-date *ds* *user-id* (:id meet) "2026-05-15")]
      (is (= "2026-05-15" (:start_date result)))
      (is (some? (:modified_at result))))))

(deftest set-meet-start-time-test
  (testing "sets start time and returns updated fields"
    (let [meet (db.meet/add-meet *ds* *user-id* "Meet")
          result (db.meet/set-meet-start-time *ds* *user-id* (:id meet) "14:30")]
      (is (= "14:30" (:start_time result)))
      (is (some? (:modified_at result)))))

  (testing "clears start time when empty string"
    (let [meet (db.meet/add-meet *ds* *user-id* "Meet2")]
      (db.meet/set-meet-start-time *ds* *user-id* (:id meet) "09:00")
      (is (nil? (:start_time (db.meet/set-meet-start-time *ds* *user-id* (:id meet) "")))))))

(deftest list-meets-exclude-by-place-test
  (let [place (db.category/add-place *ds* *user-id* "Office")
        m1 (db.meet/add-meet *ds* *user-id* "At office")
        m2 (db.meet/add-meet *ds* *user-id* "Remote")]
    (db.meet/set-meet-start-date *ds* *user-id* (:id m1) "2099-01-01")
    (db.meet/set-meet-start-date *ds* *user-id* (:id m2) "2099-01-01")
    (db.meet/categorize-meet *ds* *user-id* (:id m1) "place" (:id place))
    (let [meets (db.meet/list-meets *ds* *user-id* {:excluded-places ["Office"]})]
      (is (= 1 (count meets)))
      (is (= "Remote" (:title (first meets)))))))

(deftest list-meets-exclude-by-project-test
  (let [project (db.category/add-project *ds* *user-id* "Alpha")
        m1 (db.meet/add-meet *ds* *user-id* "Alpha standup")
        m2 (db.meet/add-meet *ds* *user-id* "General")]
    (db.meet/set-meet-start-date *ds* *user-id* (:id m1) "2099-01-01")
    (db.meet/set-meet-start-date *ds* *user-id* (:id m2) "2099-01-01")
    (db.meet/categorize-meet *ds* *user-id* (:id m1) "project" (:id project))
    (let [meets (db.meet/list-meets *ds* *user-id* {:excluded-projects ["Alpha"]})]
      (is (= ["General"] (mapv :title meets))))))
