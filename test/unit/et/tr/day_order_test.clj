(ns et.tr.day-order-test
  (:require [clojure.test :refer [deftest testing is]]
            [et.tr.day-order :as day-order]))

(defn- meet [id time]
  {:item-type :meet :id id :title (str "meet-" id) :start_time time})

(defn- due [id time]
  {:item-type :task :id id :title (str "due-" id) :due_time time})

(defn- flagged [id sort-order]
  {:item-type :task :id id :title (str "flagged-" id) :day-flagged? true :sort_order sort-order})

(defn- titles [items]
  (mapv :title (day-order/sort-items items)))

;; The list as today.cljs hands it over: tasks due that day, then that day's
;; meets, then the tasks merely flagged for the day.
(def ^:private a-day
  (concat [(due 1 nil) (due 2 "15:30")]
          [(meet 1 "08:30") (meet 2 "15:00")]
          [(flagged 3 1.0) (flagged 4 2.0) (flagged 5 3.0)]))

(deftest untouched-day-keeps-its-time-order
  (testing "untimed first, then the timed items by time, then the flagged tail"
    (is (= ["due-1" "meet-1" "meet-2" "due-2" "flagged-3" "flagged-4" "flagged-5"]
           (titles a-day)))))

(deftest flagged-tasks-follow-their-shared-sort-order
  (testing "the tail keeps the manual order of the Tasks page until it is dragged"
    (is (= ["flagged-5" "flagged-4" "flagged-3"]
           (titles [(flagged 3 3.0) (flagged 4 2.0) (flagged 5 1.0)]))))

  (testing "negative and fractional sort orders stay in the tail, in order"
    (is (= ["flagged-3" "flagged-4" "flagged-5" "flagged-6"]
           (titles [(flagged 5 0.5) (flagged 3 -12000.0) (flagged 6 999999.0) (flagged 4 0.0)]))))

  (testing "the whole tail sorts after every timed item"
    (is (= ["meet-1" "flagged-3"]
           (titles [(flagged 3 -1e9) (meet 1 "23:59")])))))

(deftest items-that-derive-the-same-value-keep-the-order-they-came-in
  (testing "two meets at the same time, and untimed items before them"
    (is (= ["due-1" "due-2" "meet-1" "meet-2"]
           (titles [(due 1 nil) (due 2 nil) (meet 1 "09:00") (meet 2 "09:00")])))))

(deftest a-stored-value-lifts-a-task-into-place
  (testing "a task dragged between two meets sits between them"
    (let [dragged (assoc (flagged 5 3.0) :day_order 705.0)]
      (is (= ["due-1" "meet-1" "flagged-5" "meet-2" "due-2" "flagged-3" "flagged-4"]
             (titles (replace {(flagged 5 3.0) dragged} a-day))))))

  (testing "a task due that day can be dragged to the top of its day"
    (let [dragged (assoc (due 2 "15:30") :day_order -2.0)]
      (is (= ["due-2" "due-1" "meet-1" "meet-2" "flagged-3" "flagged-4" "flagged-5"]
             (titles (replace {(due 2 "15:30") dragged} a-day))))))

  (testing "a stored 0.0 counts as midnight, not as no value at all — the tail would sort it last"
    (is (= ["due-1" "flagged-5" "meet-1"]
           (titles [(due 1 nil) (meet 1 "08:30") (assoc (flagged 5 3.0) :day_order 0.0)])))))

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
      (is (= (+ 1.0 (day-order/axis (flagged 5 3.0))) (at (flagged 5 3.0) "after"))))

    (testing "before the first item it steps below it"
      (is (= -2.0 (at (due 1 nil) "before"))))

    (testing "an item that is not in the list has no slot"
      (is (nil? (at (meet 9 "10:00") "after")))
      (is (nil? (at (due 9 "10:00") "before"))))

    (testing "a meet and a task with the same id are told apart"
      (is (not= (at (meet 1 "08:30") "after") (at (due 1 nil) "after"))))))

(deftest insert-value-puts-the-task-where-it-was-dropped
  (testing "the value it returns sorts the task into the dropped-on position"
    (let [dragged (flagged 5 3.0)
          rest-of-day (remove #(day-order/same-item? % dragged) a-day)]
      (doseq [[target position expected]
              [[(meet 1 "08:30") "after" ["due-1" "meet-1" "flagged-5" "meet-2" "due-2" "flagged-3" "flagged-4"]]
               [(due 1 nil) "before" ["flagged-5" "due-1" "meet-1" "meet-2" "due-2" "flagged-3" "flagged-4"]]
               [(due 2 "15:30") "after" ["due-1" "meet-1" "meet-2" "due-2" "flagged-5" "flagged-3" "flagged-4"]]
               [(flagged 3 1.0) "after" ["due-1" "meet-1" "meet-2" "due-2" "flagged-3" "flagged-5" "flagged-4"]]
               [(flagged 4 2.0) "after" ["due-1" "meet-1" "meet-2" "due-2" "flagged-3" "flagged-4" "flagged-5"]]]]
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

(deftest stored-values-survive-the-day-changing-around-them
  (testing "adding and removing other items never shifts what anything derives"
    (let [dragged (assoc (flagged 5 3.0) :day_order 705.0)
          day (replace {(flagged 5 3.0) dragged} a-day)]
      (is (= ["due-1" "meet-1" "flagged-5" "meet-2" "due-2" "flagged-3" "flagged-4"]
             (titles day)))
      (testing "a meet added later in the day leaves it between the same two"
        (is (= ["due-1" "meet-1" "flagged-5" "meet-2" "due-2" "meet-6" "flagged-3" "flagged-4"]
               (titles (conj (vec day) (meet 6 "18:00"))))))
      (testing "a flagged task added to the tail leaves it in place"
        (is (= ["due-1" "meet-1" "flagged-5" "meet-2" "due-2" "flagged-7" "flagged-3" "flagged-4"]
               (titles (conj (vec day) (flagged 7 0.5))))))
      (testing "removing the meet it was dropped after leaves it before the next one"
        (is (= ["due-1" "flagged-5" "meet-2" "due-2" "flagged-3" "flagged-4"]
               (titles (remove #(day-order/same-item? % (meet 1 "08:30")) day))))))))
