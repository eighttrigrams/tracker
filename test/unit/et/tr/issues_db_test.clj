(ns et.tr.issues-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db.issue :as db.issue]
            [et.tr.db.task :as db.task]
            [et.tr.db.category :as db.category]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(deftest add-issue-test
  (testing "new issue is created with defaults and empty category/task lists"
    (let [issue (db.issue/add-issue *ds* *user-id* "Leaky roof")]
      (is (some? (:id issue)))
      (is (= "Leaky roof" (:title issue)))
      (is (= "both" (:scope issue)))
      (is (= [] (:people issue)))
      (is (= [] (:tasks issue))))))

(deftest list-issues-test
  (testing "lists the caller's issues"
    (db.issue/add-issue *ds* *user-id* "Issue A")
    (db.issue/add-issue *ds* *user-id* "Issue B")
    (let [issues (db.issue/list-issues *ds* *user-id* {})]
      (is (= 2 (count issues)))
      (is (= #{"Issue A" "Issue B"} (set (map :title issues)))))))

(deftest list-issues-search-test
  (testing "search filters by title"
    (db.issue/add-issue *ds* *user-id* "Kitchen renovation")
    (db.issue/add-issue *ds* *user-id* "Garden fence")
    (let [issues (db.issue/list-issues *ds* *user-id* {:search-term "kitchen"})]
      (is (= ["Kitchen renovation"] (mapv :title issues))))))

(deftest list-issues-honors-limit-test
  (testing ":limit caps the rows returned"
    (dotimes [i 6] (db.issue/add-issue *ds* *user-id* (str "Issue " i)))
    (is (= 6 (count (db.issue/list-issues *ds* *user-id* {}))))
    (is (= 2 (count (db.issue/list-issues *ds* *user-id* {:limit 2}))))))

(deftest get-issue-test
  (testing "fetches a single issue with its tasks"
    (let [issue (db.issue/add-issue *ds* *user-id* "Bug hunt")
          task (db.task/add-task *ds* *user-id* "Reproduce it")]
      (db.issue/set-task-issue *ds* *user-id* (:id task) (:id issue))
      (let [fetched (db.issue/get-issue *ds* *user-id* (:id issue))]
        (is (= "Bug hunt" (:title fetched)))
        (is (= [{:id (:id task) :title "Reproduce it" :done 0}] (:tasks fetched)))))))

(deftest update-issue-test
  (testing "updates mutable fields"
    (let [issue (db.issue/add-issue *ds* *user-id* "Old title")
          result (db.issue/update-issue *ds* *user-id* (:id issue)
                                        {:title "New title" :description "desc"})]
      (is (= "New title" (:title result)))
      (is (= "desc" (:description result))))))

(deftest set-issue-scope-is-ownership-scoped-test
  (testing "set-issue-field respects ownership"
    (let [issue (db.issue/add-issue *ds* *user-id* "Scoped")]
      (is (nil? (db.issue/set-issue-field *ds* (inc *user-id*) (:id issue) :scope "work")))
      (is (= "both" (:scope (db.issue/get-issue *ds* *user-id* (:id issue))))))))

(deftest categorize-issue-test
  (testing "categorizing attaches a project to an issue"
    (let [issue (db.issue/add-issue *ds* *user-id* "Categorized")
          project (db.category/add-project *ds* *user-id* "Alpha")]
      (db.issue/categorize-issue *ds* *user-id* (:id issue) "project" (:id project))
      (let [fetched (db.issue/get-issue *ds* *user-id* (:id issue))]
        (is (= [(:id project)] (mapv :id (:projects fetched))))))))

(deftest delete-issue-test
  (testing "deleting an issue removes it"
    (let [issue (db.issue/add-issue *ds* *user-id* "Doomed")]
      (is (:success (db.issue/delete-issue *ds* *user-id* (:id issue))))
      (is (nil? (db.issue/get-issue *ds* *user-id* (:id issue)))))))
