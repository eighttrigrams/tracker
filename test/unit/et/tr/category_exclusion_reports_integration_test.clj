(ns et.tr.category-exclusion-reports-integration-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.integration-helpers :refer [GET-json with-integration-db *ds* *user-id*]]
            [et.tr.db.category :as db.category]
            [et.tr.db.category-rule :as db.category-rule]
            [et.tr.db.task :as db.task]
            [et.tr.db.meet :as db.meet]
            [et.tr.db.issue :as db.issue]
            [et.tr.db.journal-entry :as db.journal-entry])
  (:import [java.time LocalDate]))

(use-fixtures :each with-integration-db)

(defn- titles [coll] (set (map :title coll)))

;; /api/reports is the one endpoint that fans a single request out over four
;; list fns plus their four has_more probes, so it is the one place where a
;; dropped :excluded-categories would hide in an otherwise-working feature.
;; Everything is dated inside a two-week window: tasks/issues/entries land in
;; the current week, meets a week back (a meet dated today is not yet past).
(defn- seed-source-pair! [carried-category-id]
  (let [done (db.task/add-task *ds* *user-id* "task carried")
        done-plain (db.task/add-task *ds* *user-id* "task plain")
        resolved (db.issue/add-issue *ds* *user-id* "issue carried")
        resolved-plain (db.issue/add-issue *ds* *user-id* "issue plain")
        meet (db.meet/add-meet *ds* *user-id* "meet carried")
        meet-plain (db.meet/add-meet *ds* *user-id* "meet plain")
        entry (db.journal-entry/add-journal-entry *ds* *user-id* "entry carried" "both")
        entry-plain (db.journal-entry/add-journal-entry *ds* *user-id* "entry plain" "both")
        last-week (str (.minusDays (LocalDate/now) 7))
        today (str (LocalDate/now))]
    (db.task/categorize-task *ds* *user-id* (:id done) "project" carried-category-id)
    (db.issue/categorize-issue *ds* *user-id* (:id resolved) "project" carried-category-id)
    (db.meet/categorize-meet *ds* *user-id* (:id meet) "project" carried-category-id)
    (db.journal-entry/categorize-journal-entry *ds* *user-id* (:id entry) "project" carried-category-id)
    (doseq [t [done done-plain]] (db.task/set-task-done *ds* *user-id* (:id t) true))
    (doseq [i [resolved resolved-plain]] (db.issue/set-issue-resolved *ds* *user-id* (:id i) true))
    (doseq [m [meet meet-plain]] (db.meet/set-meet-start-date *ds* *user-id* (:id m) last-week))
    (doseq [e [entry entry-plain]]
      (db.journal-entry/set-journal-entry-field *ds* *user-id* (:id e) :entry_date today))))

(deftest reports-honour-the-exclusion-across-all-four-sources-test
  (let [plurama (db.category/add-project *ds* *user-id* "plurama")
        tracker (db.category/add-project *ds* *user-id* "tracker")]
    ;; Carried category is the rule's TARGET, so only a backend-expanded closure
    ;; hides these rows — a name match on the seed alone would keep them.
    (db.category-rule/add-rule *ds* *user-id* "project" (:id plurama) "project" (:id tracker))
    (seed-source-pair! (:id tracker))
    (testing "without the param every seeded row is reported"
      (let [{:keys [status body]} (GET-json "/api/reports?weekOffset=0&weekLimit=2")]
        (is (= 200 status))
        (is (= #{"task carried" "task plain"} (titles (:tasks body))))
        (is (= #{"issue carried" "issue plain"} (titles (:issues body))))
        (is (= #{"meet carried" "meet plain"} (titles (:meets body))))
        (is (= #{"entry carried" "entry plain"} (titles (:journal_entries body))))))
    (testing "excluded-projects hides the carriers in all four sources"
      (let [{:keys [body]} (GET-json "/api/reports?weekOffset=0&weekLimit=2&excluded-projects=plurama")]
        (is (= #{"task plain"} (titles (:tasks body))))
        (is (= #{"issue plain"} (titles (:issues body))))
        (is (= #{"meet plain"} (titles (:meets body))))
        (is (= #{"entry plain"} (titles (:journal_entries body))))))))
