(ns et.tr.scheduling-test
  (:require [clojure.test :refer [deftest testing is]]
            [et.tr.scheduling :as scheduling]))

(def today "2026-04-06")
(def tomorrow "2026-04-07")
(def today+2 "2026-04-08")
(def today+3 "2026-04-09")
(def today+4 "2026-04-10")
(def today+5 "2026-04-11")
(def today+6 "2026-04-12")

(deftest meets-to-create-do-nothing-when-meet-exists-beyond-window
  (testing "if a meet exists after Today+4, do nothing"
    (is (= []
           (scheduling/meets-to-create today
             [today tomorrow today+2]
             #{today+5})))))

(deftest meets-to-create-today-scheduled-no-meet
  (testing "if today is scheduled and no meet exists, create today"
    (is (= [today tomorrow today+2]
           (scheduling/meets-to-create today
             [today tomorrow today+2]
             #{})))))

(deftest meets-to-create-today-scheduled-already-exists
  (testing "if today is scheduled and meet exists, don't recreate"
    (is (= [tomorrow today+2]
           (scheduling/meets-to-create today
             [today tomorrow today+2]
             #{today})))))

(deftest meets-to-create-fill-after-last-existing
  (testing "spec example: every day scheduled, only Today+2 exists -> create Today+3 and Today+4"
    (is (= [today today+3 today+4]
           (scheduling/meets-to-create today
             [today tomorrow today+2 today+3 today+4 today+5]
             #{today+2})))))

(deftest meets-to-create-fill-after-last-existing-no-today
  (testing "multiple scheduled, some exist, today not scheduled"
    (is (= [today+4]
           (scheduling/meets-to-create today
             [tomorrow today+2 today+3 today+4 today+5]
             #{tomorrow today+3})))))

(deftest meets-to-create-only-one-in-window-create-plus-beyond
  (testing "only one scheduled in window -> create it + one after Today+4"
    (is (= [today+3 today+5]
           (scheduling/meets-to-create today
             [today+3 today+5]
             #{})))))

(deftest meets-to-create-only-today-in-window-create-plus-beyond
  (testing "only today scheduled in window -> create today + one after Today+4"
    (is (= [today today+5]
           (scheduling/meets-to-create today
             [today today+5]
             #{})))))

(deftest meets-to-create-only-one-in-window-already-exists
  (testing "only one in window and it exists -> just create beyond"
    (is (= [today+5]
           (scheduling/meets-to-create today
             [today+3 today+5]
             #{today+3})))))

(deftest meets-to-create-nothing-scheduled
  (testing "nothing scheduled -> nothing to create"
    (is (= []
           (scheduling/meets-to-create today [] #{})))))

(deftest meets-to-create-all-exist
  (testing "all scheduled meets already exist"
    (is (= []
           (scheduling/meets-to-create today
             [today tomorrow today+2]
             #{today tomorrow today+2})))))

(deftest meets-to-create-multiple-in-window-none-exist
  (testing "multiple scheduled in window, none exist -> create all"
    (is (= [tomorrow today+3]
           (scheduling/meets-to-create today
             [tomorrow today+3 today+6]
             #{})))))
