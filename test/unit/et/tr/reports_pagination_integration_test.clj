(ns et.tr.reports-pagination-integration-test
  (:require [clojure.test :refer :all]
            [et.tr.integration-helpers :refer :all]
            [et.tr.db :as db]
            [next.jdbc :as jdbc]
            [honey.sql :as sql])
  (:import [java.time LocalDate]))

(use-fixtures :each with-integration-db)

(defn- days-ago [n]
  (str (.minusDays (LocalDate/now) n)))

(defn- insert-task! [title done-at modified-at]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:insert-into :tasks
                 :values [{:title title :user_id *user-id* :done 1
                           :done_at done-at :modified_at modified-at
                           :sort_order 1.0 :scope "both"}]})))

(defn- insert-meet! [title start-date]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:insert-into :meets
                 :values [{:title title :user_id *user-id* :start_date start-date
                           :modified_at start-date :sort_order 1.0 :scope "both" :archived 0}]})))

(defn- insert-journal-entry! [title entry-date]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:insert-into :journal_entries
                 :values [{:title title :user_id *user-id* :entry_date entry-date
                           :sort_order 1.0 :scope "both"}]})))

(defn- titles [coll] (set (map :title coll)))

(defn- seed-spread! []
  (insert-task! "task-now" (str (days-ago 0) " 12:00:00") (str (days-ago 0) " 12:00:00"))
  (insert-task! "task-recent" (str (days-ago 7) " 12:00:00") (str (days-ago 7) " 12:00:00"))
  (insert-task! "task-null-done" nil (str (days-ago 7) " 12:00:00"))
  (insert-meet! "meet-recent" (days-ago 7))
  (insert-journal-entry! "journal-recent" (days-ago 0))
  (insert-task! "task-old" (str (days-ago 35) " 12:00:00") (str (days-ago 35) " 12:00:00"))
  (insert-meet! "meet-old" (days-ago 35))
  (insert-journal-entry! "journal-old" (days-ago 35)))

(deftest envelope-shape
  (testing "response keeps the three sources and adds :has_more"
    (seed-spread!)
    (let [{:keys [status body]} (GET-json "/api/reports?weekOffset=0&weekLimit=4")]
      (is (= 200 status))
      (is (contains? body :tasks))
      (is (contains? body :meets))
      (is (contains? body :journal_entries))
      (is (contains? body :has_more)))))

(deftest first-window-is-most-recent-four-weeks
  (testing "weekOffset=0 returns only the most recent 4 weeks across all sources"
    (seed-spread!)
    (let [{:keys [body]} (GET-json "/api/reports?weekOffset=0&weekLimit=4")]
      (is (= #{"task-now" "task-recent" "task-null-done"} (titles (:tasks body))))
      (is (= #{"meet-recent"} (titles (:meets body))))
      (is (= #{"journal-recent"} (titles (:journal_entries body))))
      (is (true? (:has_more body))))))

(deftest leading-week-may-be-partial
  (testing "a task completed today (mid-week) appears in the first window"
    (insert-task! "task-now" (str (days-ago 0) " 09:00:00") (str (days-ago 0) " 09:00:00"))
    (let [{:keys [body]} (GET-json "/api/reports?weekOffset=0&weekLimit=4")]
      (is (contains? (titles (:tasks body)) "task-now")))))

(deftest second-window-pages-back-without-overlap
  (testing "weekOffset=4 returns the next 4 weeks back, disjoint from the first window"
    (seed-spread!)
    (let [w0 (:body (GET-json "/api/reports?weekOffset=0&weekLimit=4"))
          w1 (:body (GET-json "/api/reports?weekOffset=4&weekLimit=4"))]
      (is (= #{"task-old"} (titles (:tasks w1))))
      (is (= #{"meet-old"} (titles (:meets w1))))
      (is (= #{"journal-old"} (titles (:journal_entries w1))))
      (testing "no overlap between adjacent windows"
        (is (empty? (clojure.set/intersection (titles (:tasks w0)) (titles (:tasks w1)))))
        (is (empty? (clojure.set/intersection (titles (:meets w0)) (titles (:meets w1)))))
        (is (empty? (clojure.set/intersection (titles (:journal_entries w0)) (titles (:journal_entries w1))))))
      (testing "no gap: union of both windows covers every seeded item"
        (is (= #{"task-now" "task-recent" "task-null-done" "task-old"}
               (clojure.set/union (titles (:tasks w0)) (titles (:tasks w1)))))
        (is (= #{"meet-recent" "meet-old"}
               (clojure.set/union (titles (:meets w0)) (titles (:meets w1)))))
        (is (= #{"journal-recent" "journal-old"}
               (clojure.set/union (titles (:journal_entries w0)) (titles (:journal_entries w1)))))))))

(deftest has-more-true-iff-older-items-exist
  (testing "has_more is true while older items remain and false once past them"
    (seed-spread!)
    (is (true? (:has_more (:body (GET-json "/api/reports?weekOffset=0&weekLimit=4")))))
    (is (false? (:has_more (:body (GET-json "/api/reports?weekOffset=4&weekLimit=4")))))
    (is (false? (:has_more (:body (GET-json "/api/reports?weekOffset=8&weekLimit=4")))))))

(deftest has-more-respects-items-filter
  (testing "with items=journals, has_more reflects only older journal entries"
    (insert-task! "task-old" (str (days-ago 35) " 12:00:00") (str (days-ago 35) " 12:00:00"))
    (insert-journal-entry! "journal-recent" (days-ago 0))
    (let [{:keys [body]} (GET-json "/api/reports?weekOffset=0&weekLimit=4&items=journals")]
      (is (empty? (:tasks body)))
      (is (false? (:has_more body))))
    (testing "an older journal entry flips has_more back on"
      (insert-journal-entry! "journal-old" (days-ago 35))
      (let [{:keys [body]} (GET-json "/api/reports?weekOffset=0&weekLimit=4&items=journals")]
        (is (true? (:has_more body)))))))

(deftest no-params-returns-full-history
  (testing "without pagination params the endpoint returns everything (machine-user contract)"
    (seed-spread!)
    (let [{:keys [body]} (GET-json "/api/reports")]
      (is (= #{"task-now" "task-recent" "task-null-done" "task-old"} (titles (:tasks body))))
      (is (= #{"meet-recent" "meet-old"} (titles (:meets body))))
      (is (= #{"journal-recent" "journal-old"} (titles (:journal_entries body))))
      (is (false? (:has_more body))))))
