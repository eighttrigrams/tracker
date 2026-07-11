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

(defn- insert-issue! [title resolved resolved-at]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:insert-into :issues
                 :values [{:title title :user_id *user-id* :resolved resolved
                           :resolved_at resolved-at
                           :modified_at (or resolved-at (str (days-ago 0) " 12:00:00"))
                           :sort_order 1.0 :scope "both"}]})))

(defn- titles [coll] (set (map :title coll)))

;; 7 days ago is always exactly one ISO week back (same weekday), so it lands
;; in the immediately-previous week regardless of which day the suite runs.
;; 35 days ago is five weeks back — outside any window we exercise here.
;; Reports only ever show *past* meets (a meet dated today is not yet past), so
;; the meet fixtures live one week back, where they are both past and land in
;; the previous ISO week — never in the current-week default window.
(defn- seed-spread! []
  (insert-task! "task-now" (str (days-ago 0) " 12:00:00") (str (days-ago 0) " 12:00:00"))
  (insert-task! "task-recent" (str (days-ago 7) " 12:00:00") (str (days-ago 7) " 12:00:00"))
  (insert-meet! "meet-recent" (days-ago 7))
  (insert-journal-entry! "journal-now" (days-ago 0))
  (insert-journal-entry! "journal-recent" (days-ago 7))
  (insert-issue! "issue-now" 1 (str (days-ago 0) " 12:00:00"))
  (insert-issue! "issue-recent" 1 (str (days-ago 7) " 12:00:00"))
  (insert-task! "task-old" (str (days-ago 35) " 12:00:00") (str (days-ago 35) " 12:00:00"))
  (insert-issue! "issue-old" 1 (str (days-ago 35) " 12:00:00")))

(deftest envelope-shape
  (testing "response carries the four sources and :has_more"
    (seed-spread!)
    (let [{:keys [status body]} (GET-json "/api/reports?weekOffset=0&weekLimit=1")]
      (is (= 200 status))
      (is (contains? body :issues))
      (is (contains? body :tasks))
      (is (contains? body :meets))
      (is (contains? body :journal_entries))
      (is (contains? body :has_more)))))

(deftest default-window-is-current-week-only
  (testing "without params the endpoint returns the current week (scope 1 default)"
    (seed-spread!)
    (let [{:keys [body]} (GET-json "/api/reports")]
      (is (= #{"task-now"} (titles (:tasks body))))
      (is (= #{"journal-now"} (titles (:journal_entries body))))
      (is (= #{"issue-now"} (titles (:issues body))))
      (testing "last week's items (incl. past meets) are not in the default window"
        (is (empty? (:meets body)))
        (is (not (contains? (titles (:tasks body)) "task-recent")))
        (is (not (contains? (titles (:issues body)) "issue-recent")))))))

(deftest scope-widens-window-backward
  (testing "weekLimit=2 pulls the previous week in alongside the current one"
    (seed-spread!)
    (let [{:keys [body]} (GET-json "/api/reports?weekOffset=0&weekLimit=2")]
      (is (= #{"task-now" "task-recent"} (titles (:tasks body))))
      (is (= #{"meet-recent"} (titles (:meets body))))
      (is (= #{"journal-now" "journal-recent"} (titles (:journal_entries body))))
      (is (= #{"issue-now" "issue-recent"} (titles (:issues body))))
      (testing "five-weeks-old items stay out"
        (is (not (contains? (titles (:tasks body)) "task-old")))
        (is (not (contains? (titles (:issues body)) "issue-old")))))))

(deftest offset-shifts-window-backward
  (testing "weekOffset=1&weekLimit=1 shows only the previous week, disjoint from this week"
    (seed-spread!)
    (let [{:keys [body]} (GET-json "/api/reports?weekOffset=1&weekLimit=1")]
      (is (= #{"task-recent"} (titles (:tasks body))))
      (is (= #{"meet-recent"} (titles (:meets body))))
      (is (= #{"journal-recent"} (titles (:journal_entries body))))
      (is (= #{"issue-recent"} (titles (:issues body))))
      (is (not (contains? (titles (:tasks body)) "task-now"))))))

(deftest items-filter-selects-sources
  (testing "the items param controls which sources come back"
    (seed-spread!)
    ;; weekLimit=2 so the previous-week (past) meet fixture is in-window.
    (let [g (fn [items] (:body (GET-json (str "/api/reports?weekOffset=0&weekLimit=2"
                                              (when items (str "&items=" items))))))]
      (testing "all includes every source"
        (let [b (g "all")]
          (is (seq (:issues b))) (is (seq (:tasks b)))
          (is (seq (:meets b))) (is (seq (:journal_entries b)))))
      (testing "issues-tasks-meets drops journals"
        (let [b (g "issues-tasks-meets")]
          (is (seq (:issues b))) (is (seq (:tasks b))) (is (seq (:meets b)))
          (is (empty? (:journal_entries b)))))
      (testing "issues-tasks drops meets and journals"
        (let [b (g "issues-tasks")]
          (is (seq (:issues b))) (is (seq (:tasks b)))
          (is (empty? (:meets b))) (is (empty? (:journal_entries b)))))
      (testing "journals keeps only journals"
        (let [b (g "journals")]
          (is (empty? (:issues b))) (is (empty? (:tasks b))) (is (empty? (:meets b)))
          (is (seq (:journal_entries b))))))))

(deftest only-resolved-issues-appear
  (testing "unresolved issues are never reported, even inside the window"
    (insert-issue! "issue-resolved" 1 (str (days-ago 0) " 12:00:00"))
    (insert-issue! "issue-open" 0 nil)
    (let [{:keys [body]} (GET-json "/api/reports?weekOffset=0&weekLimit=1")]
      (is (= #{"issue-resolved"} (titles (:issues body))))
      (is (not (contains? (titles (:issues body)) "issue-open"))))))

(deftest issues-windowed-on-resolved-at
  (testing "resolved issues page back by week just like tasks"
    (seed-spread!)
    (let [wide (:body (GET-json "/api/reports?weekOffset=0&weekLimit=6"))]
      (is (contains? (titles (:issues wide)) "issue-old"))
      (is (contains? (titles (:issues wide)) "issue-now"))
      (is (contains? (titles (:issues wide)) "issue-recent")))))

(deftest has-more-true-iff-older-items-exist
  (testing "has_more is true while older items remain and false once past them"
    (seed-spread!)
    (is (true? (:has_more (:body (GET-json "/api/reports?weekOffset=0&weekLimit=1")))))
    (is (false? (:has_more (:body (GET-json "/api/reports?weekOffset=0&weekLimit=52")))))))

(deftest has-more-reflects-only-selected-sources
  (testing "with items=journals, has_more ignores older issues/tasks"
    (insert-task! "task-old" (str (days-ago 35) " 12:00:00") (str (days-ago 35) " 12:00:00"))
    (insert-issue! "issue-old" 1 (str (days-ago 35) " 12:00:00"))
    (insert-journal-entry! "journal-now" (days-ago 0))
    (let [{:keys [body]} (GET-json "/api/reports?weekOffset=0&weekLimit=1&items=journals")]
      (is (empty? (:tasks body)))
      (is (empty? (:issues body)))
      (is (false? (:has_more body))))
    (testing "an older journal entry flips has_more back on"
      (insert-journal-entry! "journal-old" (days-ago 35))
      (let [{:keys [body]} (GET-json "/api/reports?weekOffset=0&weekLimit=1&items=journals")]
        (is (true? (:has_more body)))))))
