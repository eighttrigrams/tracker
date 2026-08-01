(ns et.tr.day-order-test
  (:require [clojure.test :refer [deftest testing is]]
            [et.tr.day-order :as day-order]))

(defn- meet [id time]
  {:item-type :meet :id id :title (str "meet-" id) :start_time time})

(defn- due [id time]
  {:item-type :task :id id :title (str "due-" id) :due_time time})

(defn- flagged [id day-order]
  {:item-type :task :id id :title (str "flagged-" id) :day-flagged? true :day_order day-order})

(defn- titles [items]
  (mapv :title (day-order/sort-items items)))

;; The list as the day-list builder hands it over: tasks due that day, then that
;; day's meets, then the tasks merely flagged for the day — the last carrying the
;; positions they were given when they joined it.
(def ^:private a-day
  (concat [(due 1 nil) (due 2 "15:30")]
          [(meet 1 "08:30") (meet 2 "15:00")]
          [(flagged 3 1441.0) (flagged 4 1442.0) (flagged 5 1443.0)]))

(deftest untouched-day-keeps-its-time-order
  (testing "untimed first, then the timed items by time, then the flagged tail"
    (is (= ["due-1" "meet-1" "meet-2" "due-2" "flagged-3" "flagged-4" "flagged-5"]
           (titles a-day)))))

(deftest flagged-tasks-follow-the-positions-they-were-given
  (testing "the tail is ordered by its own column and nothing else"
    (is (= ["flagged-5" "flagged-4" "flagged-3"]
           (titles [(flagged 3 1443.0) (flagged 4 1442.0) (flagged 5 1441.0)]))))

  (testing "the Tasks page order never reaches the day axis"
    (is (= ["flagged-3" "flagged-4"]
           (titles [(assoc (flagged 3 1441.0) :sort_order 900.0)
                    (assoc (flagged 4 1442.0) :sort_order -900.0)]))))

  (testing "a flagged task that carries no position of its own sits after the timed block"
    (is (= ["meet-1" "flagged-9"]
           (titles [(flagged 9 nil) (meet 1 "23:59")])))))

(deftest items-that-derive-the-same-value-keep-the-order-they-came-in
  (testing "two meets at the same time, and untimed items before them"
    (is (= ["due-1" "due-2" "meet-1" "meet-2"]
           (titles [(due 1 nil) (due 2 nil) (meet 1 "09:00") (meet 2 "09:00")])))))

(deftest a-stored-value-lifts-a-task-into-place
  (testing "a task dragged between two meets sits between them"
    (let [dragged (assoc (flagged 5 1443.0) :day_order 705.0)]
      (is (= ["due-1" "meet-1" "flagged-5" "meet-2" "due-2" "flagged-3" "flagged-4"]
             (titles (replace {(flagged 5 1443.0) dragged} a-day))))))

  (testing "a task due that day can be dragged to the top of its day"
    (let [dragged (assoc (due 2 "15:30") :day_order -2.0)]
      (is (= ["due-2" "due-1" "meet-1" "meet-2" "flagged-3" "flagged-4" "flagged-5"]
             (titles (replace {(due 2 "15:30") dragged} a-day))))))

  (testing "a stored 0.0 counts as midnight, not as no value at all — the tail would sort it last"
    (is (= ["due-1" "flagged-5" "meet-1"]
           (titles [(due 1 nil) (meet 1 "08:30") (assoc (flagged 5 1443.0) :day_order 0.0)])))))

(deftest insert-value-lands-between-the-target-and-its-neighbour
  (let [items a-day
        at #(day-order/insert-value items % %2)]
    (testing "dropping on the lower half of an item goes after it"
      (is (= 705.0 (at (meet 1 "08:30") "after")))
      (is (= (/ (+ 900.0 930.0) 2) (at (meet 2 "15:00") "after"))))

    (testing "dropping on the upper half goes before it"
      (is (= 705.0 (at (meet 2 "15:00") "before")))
      (is (= (/ (+ 510.0 -1.0) 2) (at (meet 1 "08:30") "before"))))

    (testing "past the last item there is no neighbour, so it steps beyond it"
      (is (= 1444.0 (at (flagged 5 1443.0) "after"))))

    (testing "before the first item it steps below it"
      (is (= -2.0 (at (due 1 nil) "before"))))

    (testing "an item that is not in the list has no slot"
      (is (nil? (at (meet 9 "10:00") "after")))
      (is (nil? (at (due 9 "10:00") "before"))))

    (testing "a meet and a task with the same id are told apart"
      (is (not= (at (meet 1 "08:30") "after") (at (due 1 nil) "after"))))))

(deftest insert-value-puts-the-task-where-it-was-dropped
  (testing "the value it returns sorts the task into the dropped-on position"
    (let [dragged (flagged 5 1443.0)
          rest-of-day (remove #(day-order/same-item? % dragged) a-day)]
      (doseq [[target position expected]
              [[(meet 1 "08:30") "after" ["due-1" "meet-1" "flagged-5" "meet-2" "due-2" "flagged-3" "flagged-4"]]
               [(due 1 nil) "before" ["flagged-5" "due-1" "meet-1" "meet-2" "due-2" "flagged-3" "flagged-4"]]
               [(due 2 "15:30") "after" ["due-1" "meet-1" "meet-2" "due-2" "flagged-5" "flagged-3" "flagged-4"]]
               [(flagged 3 1441.0) "after" ["due-1" "meet-1" "meet-2" "due-2" "flagged-3" "flagged-5" "flagged-4"]]
               [(flagged 4 1442.0) "after" ["due-1" "meet-1" "meet-2" "due-2" "flagged-3" "flagged-4" "flagged-5"]]]]
        (let [value (day-order/insert-value a-day target position)]
          (is (= expected (titles (conj (vec rest-of-day) (assoc dragged :day_order value))))
              (str position " " (:title target))))))))

(deftest a-run-of-equal-values-is-not-split
  (testing "a drop against untimed items goes before or after the whole run"
    (let [items [(due 1 nil) (due 2 nil) (meet 1 "09:00")]]
      (is (= -2.0 (day-order/insert-value items (due 2 nil) "before")))
      (is (= (/ (+ -1.0 540.0) 2) (day-order/insert-value items (due 1 nil) "after")))))

  (testing "a drop against two meets at the same time goes outside both"
    (let [items [(meet 1 "09:00") (meet 2 "09:00") (meet 3 "10:00")]]
      (is (= (/ (+ 540.0 600.0) 2) (day-order/insert-value items (meet 1 "09:00") "after")))
      (is (= 539.0 (day-order/insert-value items (meet 2 "09:00") "before"))))))

(deftest end-value-lands-past-everything-the-day-holds
  (testing "one step past the last item, which is where a join puts a task"
    (is (= 1444.0 (day-order/end-value a-day)))
    (is (= 901.0 (day-order/end-value [(meet 1 "08:30") (meet 2 "15:00")]))))

  (testing "an empty day starts the tail band"
    (is (= 1441.0 (day-order/end-value [])))))

(deftest stored-values-survive-the-day-changing-around-them
  (testing "adding and removing other items never shifts what anything derives"
    (let [dragged (assoc (flagged 5 1443.0) :day_order 705.0)
          day (replace {(flagged 5 1443.0) dragged} a-day)]
      (is (= ["due-1" "meet-1" "flagged-5" "meet-2" "due-2" "flagged-3" "flagged-4"]
             (titles day)))
      (testing "a meet added later in the day leaves it between the same two"
        (is (= ["due-1" "meet-1" "flagged-5" "meet-2" "due-2" "meet-6" "flagged-3" "flagged-4"]
               (titles (conj (vec day) (meet 6 "18:00"))))))
      (testing "a flagged task added to the tail leaves it in place"
        (is (= ["due-1" "meet-1" "flagged-5" "meet-2" "due-2" "flagged-7" "flagged-3" "flagged-4"]
               (titles (conj (vec day) (flagged 7 1440.5))))))
      (testing "removing the meet it was dropped after leaves it before the next one"
        (is (= ["due-1" "flagged-5" "meet-2" "due-2" "flagged-3" "flagged-4"]
               (titles (remove #(day-order/same-item? % (meet 1 "08:30")) day))))))))

(def ^:private today "2026-07-15")
(def ^:private tomorrow "2026-07-16")
(def ^:private yesterday "2026-07-14")

(deftest a-day-holds-what-is-due-what-happens-and-what-is-marked-for-it
  (let [tasks [{:id 1 :title "due today" :due_date today}
               {:id 2 :title "marked for today" :today 1 :day_order 1441.0}
               {:id 3 :title "lined up for tomorrow" :lined_up_for tomorrow :day_order 1441.0}
               {:id 4 :title "on no day at all"}]
        meets [{:id 1 :title "standup" :start_date today :start_time "08:30"}
               {:id 2 :title "next week" :start_date "2026-07-22" :start_time "09:00"}]]
    (testing "today's list"
      (is (= ["due today" "standup" "marked for today"]
             (mapv :title (day-order/day-items tasks meets today today)))))

    (testing "a later day takes its members from the lined-up date"
      (is (= ["lined up for tomorrow"]
             (mapv :title (day-order/day-items tasks meets today tomorrow)))))

    (testing "tasks and meets are tagged for the caller"
      (let [items (day-order/day-items tasks meets today today)]
        (is (= [:task :meet :task] (mapv :item-type items)))
        (is (= [nil nil true] (mapv :day-flagged? items)))))))

(deftest a-task-the-day-already-holds-is-not-also-flagged-for-it
  (testing "due that day and marked for it is one item, positioned by its time"
    (let [tasks [{:id 1 :title "due and marked" :due_date today :due_time "09:00" :today 1}]]
      (is (nil? (day-order/flagged-date (first tasks) today)))
      (is (= ["due and marked"] (mapv :title (day-order/day-items tasks [] today today))))
      (is (= [nil] (mapv :day-flagged? (day-order/day-items tasks [] today today))))))

  (testing "lined up for the day it is due is the same case"
    (let [task {:id 1 :due_date tomorrow :lined_up_for tomorrow}]
      (is (nil? (day-order/flagged-date task today))))))

(deftest an-overdue-task-marked-for-today-stays-in-the-overdue-section
  (let [tasks [{:id 1 :title "forgotten" :due_date yesterday :today 1}]]
    (is (nil? (day-order/flagged-date (first tasks) today)))
    (is (empty? (day-order/day-items tasks [] today today)))))
