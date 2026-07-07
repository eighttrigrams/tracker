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

(deftest issue-urgency-default-test
  (testing "new issue defaults to 'default' urgency"
    (let [issue (db.issue/add-issue *ds* *user-id* "Fresh issue")]
      (is (= "default" (:urgency issue))))))

(deftest set-issue-urgency-test
  (testing "set-issue-field updates urgency to valid values"
    (let [issue (db.issue/add-issue *ds* *user-id* "Urgent issue")]
      (db.issue/set-issue-field *ds* *user-id* (:id issue) :urgency "urgent")
      (is (= "urgent" (:urgency (db.issue/get-issue *ds* *user-id* (:id issue)))))
      (db.issue/set-issue-field *ds* *user-id* (:id issue) :urgency "superurgent")
      (is (= "superurgent" (:urgency (db.issue/get-issue *ds* *user-id* (:id issue))))))))

(deftest set-issue-urgency-normalizes-invalid-test
  (testing "an invalid urgency value is normalized to 'default'"
    (let [issue (db.issue/add-issue *ds* *user-id* "Weird urgency")]
      (db.issue/set-issue-field *ds* *user-id* (:id issue) :urgency "urgent")
      (db.issue/set-issue-field *ds* *user-id* (:id issue) :urgency "bogus")
      (is (= "default" (:urgency (db.issue/get-issue *ds* *user-id* (:id issue))))))))

(deftest list-issues-urgency-filter-test
  (testing ":urgency \"urgent\" returns urgent and superurgent, excludes default"
    (let [plain (db.issue/add-issue *ds* *user-id* "Plain")
          urgent (db.issue/add-issue *ds* *user-id* "Urgent one")
          superurgent (db.issue/add-issue *ds* *user-id* "Superurgent one")]
      (db.issue/set-issue-field *ds* *user-id* (:id urgent) :urgency "urgent")
      (db.issue/set-issue-field *ds* *user-id* (:id superurgent) :urgency "superurgent")
      (let [rows (db.issue/list-issues *ds* *user-id* {:urgency "urgent"})]
        (is (= #{"Urgent one" "Superurgent one"} (set (map :title rows))))
        (is (not (some #(= "Plain" (:title %)) rows))))
      (let [rows (db.issue/list-issues *ds* *user-id* {:urgency "superurgent"})]
        (is (= ["Superurgent one"] (mapv :title rows))))
      (is (some? plain)))))

(deftest set-issue-urgency-is-ownership-scoped-test
  (testing "set-issue-field urgency respects ownership"
    (let [issue (db.issue/add-issue *ds* *user-id* "Owned")]
      (is (nil? (db.issue/set-issue-field *ds* (inc *user-id*) (:id issue) :urgency "urgent")))
      (is (= "default" (:urgency (db.issue/get-issue *ds* *user-id* (:id issue))))))))

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
