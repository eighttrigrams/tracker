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

;; ── items-to-create (Meeting Series + Recurring Tasks with Due Date) ──

(deftest items-to-create-do-nothing-when-item-exists-beyond-window
  (testing "if an item exists after Today+4, do nothing"
    (is (= []
           (scheduling/items-to-create today
             [today tomorrow today+2]
             #{today+5})))))

(deftest items-to-create-today-scheduled-no-item
  (testing "if today is scheduled and no item exists, create today"
    (is (= [today tomorrow today+2]
           (scheduling/items-to-create today
             [today tomorrow today+2]
             #{})))))

(deftest items-to-create-today-scheduled-already-exists
  (testing "if today is scheduled and item exists, don't recreate"
    (is (= [tomorrow today+2]
           (scheduling/items-to-create today
             [today tomorrow today+2]
             #{today})))))

(deftest items-to-create-fill-after-last-existing
  (testing "spec example: every day scheduled, only Today+2 exists -> create Today+3 and Today+4"
    (is (= [today today+3 today+4]
           (scheduling/items-to-create today
             [today tomorrow today+2 today+3 today+4 today+5]
             #{today+2})))))

(deftest items-to-create-fill-after-last-existing-no-today
  (testing "multiple scheduled, some exist, today not scheduled"
    (is (= [today+4]
           (scheduling/items-to-create today
             [tomorrow today+2 today+3 today+4 today+5]
             #{tomorrow today+3})))))

(deftest items-to-create-only-one-in-window-create-plus-beyond
  (testing "only one scheduled in window -> create it + one after Today+4"
    (is (= [today+3 today+5]
           (scheduling/items-to-create today
             [today+3 today+5]
             #{})))))

(deftest items-to-create-only-today-in-window-create-plus-beyond
  (testing "only today scheduled in window -> create today + one after Today+4"
    (is (= [today today+5]
           (scheduling/items-to-create today
             [today today+5]
             #{})))))

(deftest items-to-create-only-one-in-window-already-exists
  (testing "only one in window and it exists -> just create beyond"
    (is (= [today+5]
           (scheduling/items-to-create today
             [today+3 today+5]
             #{today+3})))))

(deftest items-to-create-nothing-scheduled
  (testing "nothing scheduled -> nothing to create"
    (is (= []
           (scheduling/items-to-create today [] #{})))))

(deftest items-to-create-all-exist
  (testing "all scheduled items already exist"
    (is (= []
           (scheduling/items-to-create today
             [today tomorrow today+2]
             #{today tomorrow today+2})))))

(deftest items-to-create-multiple-in-window-none-exist
  (testing "multiple scheduled in window, none exist -> create all"
    (is (= [tomorrow today+3]
           (scheduling/items-to-create today
             [tomorrow today+3 today+6]
             #{})))))

(deftest items-to-create-done-tasks-count-as-existing
  (testing "done tasks still count as existing for due-date type"
    (is (= [today+2]
           (scheduling/items-to-create today
             [today tomorrow today+2]
             #{today tomorrow})))))

;; ── next-item-to-create (Recurring Tasks without Due Date) ──

(deftest next-item-no-active-today-scheduled
  (testing "no active task, today is scheduled -> create today"
    (is (= today
           (scheduling/next-item-to-create today
             [today tomorrow today+2]
             false false)))))

(deftest next-item-no-active-today-not-scheduled
  (testing "no active task, today not scheduled -> create next scheduled"
    (is (= tomorrow
           (scheduling/next-item-to-create today
             [tomorrow today+3]
             false false)))))

(deftest next-item-active-task-exists
  (testing "active task exists -> create nothing"
    (is (nil?
          (scheduling/next-item-to-create today
            [today tomorrow]
            true false)))))

(deftest next-item-done-today-create-after-today
  (testing "task done today -> create next after today, skip today"
    (is (= tomorrow
           (scheduling/next-item-to-create today
             [today tomorrow today+3]
             false true)))))

(deftest next-item-done-today-no-today-scheduled
  (testing "task done today, today not scheduled -> create next"
    (is (= tomorrow
           (scheduling/next-item-to-create today
             [tomorrow today+3]
             false true)))))

(deftest next-item-nothing-scheduled
  (testing "nothing scheduled -> nothing to create"
    (is (nil?
          (scheduling/next-item-to-create today [] false false)))))

(deftest next-item-active-overrides-done-today
  (testing "active task exists even if something was done today -> do nothing"
    (is (nil?
          (scheduling/next-item-to-create today
            [today tomorrow]
            true true)))))

(deftest next-item-beyond-window-returns-nil
  (testing "next scheduled date is beyond Today+4 -> do not create"
    (is (nil?
          (scheduling/next-item-to-create today
            [today+5 today+6]
            false false)))))

(deftest next-item-done-today-beyond-window-returns-nil
  (testing "task done today, next after today is beyond window -> do not create"
    (is (nil?
          (scheduling/next-item-to-create today
            [today today+5]
            false true)))))

(deftest next-item-at-window-boundary
  (testing "next scheduled date is exactly Today+4 -> create it"
    (is (= today+4
           (scheduling/next-item-to-create today
             [today+4 today+6]
             false false)))))
