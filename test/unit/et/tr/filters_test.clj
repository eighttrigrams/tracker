(ns et.tr.filters-test
  (:require [clojure.test :refer [deftest testing is]]
            [et.tr.filters :as filters]))

(deftest apply-exclusion-filter-test
  (testing "returns all tasks when no exclusions"
    (let [tasks [{:id 1 :title "Task 1" :places [] :projects []}
                 {:id 2 :title "Task 2" :places [] :projects []}]]
      (is (= tasks (filters/apply-exclusion-filter tasks #{} #{})))))

  (testing "filters out tasks with excluded place"
    (let [tasks [{:id 1 :title "Task 1" :places [{:id 10 :name "Home"}] :projects []}
                 {:id 2 :title "Task 2" :places [{:id 20 :name "Work"}] :projects []}
                 {:id 3 :title "Task 3" :places [] :projects []}]
          result (filters/apply-exclusion-filter tasks #{10} #{})]
      (is (= 2 (count result)))
      (is (= #{"Task 2" "Task 3"} (set (map :title result))))))

  (testing "filters out tasks with excluded project"
    (let [tasks [{:id 1 :title "Task 1" :places [] :projects [{:id 100 :name "Alpha"}]}
                 {:id 2 :title "Task 2" :places [] :projects [{:id 200 :name "Beta"}]}
                 {:id 3 :title "Task 3" :places [] :projects []}]
          result (filters/apply-exclusion-filter tasks #{} #{100})]
      (is (= 2 (count result)))
      (is (= #{"Task 2" "Task 3"} (set (map :title result))))))

  (testing "filters out tasks with multiple places when one is excluded"
    (let [tasks [{:id 1 :title "Task 1" :places [{:id 10 :name "Home"} {:id 20 :name "Work"}] :projects []}]
          result (filters/apply-exclusion-filter tasks #{10} #{})]
      (is (= 0 (count result)))))

  (testing "tasks without place or project are never filtered"
    (let [tasks [{:id 1 :title "Unassigned" :places [] :projects []}]
          result (filters/apply-exclusion-filter tasks #{10 20 30} #{100 200})]
      (is (= 1 (count result)))
      (is (= "Unassigned" (:title (first result))))))

  (testing "combines place and project exclusions with OR logic"
    (let [tasks [{:id 1 :title "Home task" :places [{:id 10 :name "Home"}] :projects []}
                 {:id 2 :title "Alpha task" :places [] :projects [{:id 100 :name "Alpha"}]}
                 {:id 3 :title "Work Beta" :places [{:id 20 :name "Work"}] :projects [{:id 200 :name "Beta"}]}
                 {:id 4 :title "No category" :places [] :projects []}]
          result (filters/apply-exclusion-filter tasks #{10} #{100})]
      (is (= 2 (count result)))
      (is (= #{"Work Beta" "No category"} (set (map :title result))))))

  (testing "empty task list returns empty"
    (is (= [] (filters/apply-exclusion-filter [] #{10} #{100}))))

  (testing "nil places/projects in task are handled"
    (let [tasks [{:id 1 :title "Task" :places nil :projects nil}]
          result (filters/apply-exclusion-filter tasks #{10} #{100})]
      (is (= 1 (count result))))))

(deftest target-upcoming-tasks-count-test
  (testing "target count is defined"
    (is (= 10 filters/target-upcoming-tasks-count))))

(deftest multi-prefix-matches?-test
  (testing "empty search matches everything"
    (is (true? (filters/multi-prefix-matches? "abc def" "")))
    (is (true? (filters/multi-prefix-matches? "abc def" "   ")))
    (is (true? (filters/multi-prefix-matches? "abc def" nil))))

  (testing "single prefix matches word start"
    (is (true? (filters/multi-prefix-matches? "abc def" "ab")))
    (is (true? (filters/multi-prefix-matches? "abc def" "de"))))

  (testing "single prefix does NOT match mid-word"
    (is (false? (filters/multi-prefix-matches? "abc def" "bc")))
    (is (false? (filters/multi-prefix-matches? "abc def" "ef"))))

  (testing "multiple prefixes require ALL to match"
    (is (true? (filters/multi-prefix-matches? "abc bbab blablub" "a bb")))
    (is (true? (filters/multi-prefix-matches? "abc def ghi" "ab de")))
    (is (false? (filters/multi-prefix-matches? "abc def" "ab xyz"))))

  (testing "case insensitivity"
    (is (true? (filters/multi-prefix-matches? "ABC DEF" "ab de")))
    (is (true? (filters/multi-prefix-matches? "abc def" "AB DE"))))

  (testing "whitespace handling in search term"
    (is (true? (filters/multi-prefix-matches? "abc def" "  ab  de  ")))))

(deftest matches-scope?-test
  (testing "non-strict mode - :private includes both and private"
    (is (true? (filters/matches-scope? {:scope "private"} :private false)))
    (is (true? (filters/matches-scope? {:scope "both"} :private false)))
    (is (false? (filters/matches-scope? {:scope "work"} :private false))))

  (testing "non-strict mode - :work includes both and work"
    (is (true? (filters/matches-scope? {:scope "work"} :work false)))
    (is (true? (filters/matches-scope? {:scope "both"} :work false)))
    (is (false? (filters/matches-scope? {:scope "private"} :work false))))

  (testing "non-strict mode - :both includes all"
    (is (true? (filters/matches-scope? {:scope "private"} :both false)))
    (is (true? (filters/matches-scope? {:scope "work"} :both false)))
    (is (true? (filters/matches-scope? {:scope "both"} :both false))))

  (testing "non-strict mode - nil scope defaults to both"
    (is (true? (filters/matches-scope? {} :private false)))
    (is (true? (filters/matches-scope? {} :work false)))
    (is (true? (filters/matches-scope? {} :both false))))

  (testing "strict mode - :private only matches private"
    (is (true? (filters/matches-scope? {:scope "private"} :private true)))
    (is (false? (filters/matches-scope? {:scope "both"} :private true)))
    (is (false? (filters/matches-scope? {:scope "work"} :private true))))

  (testing "strict mode - :work only matches work"
    (is (true? (filters/matches-scope? {:scope "work"} :work true)))
    (is (false? (filters/matches-scope? {:scope "both"} :work true)))
    (is (false? (filters/matches-scope? {:scope "private"} :work true))))

  (testing "strict mode - :both only matches both"
    (is (true? (filters/matches-scope? {:scope "both"} :both true)))
    (is (false? (filters/matches-scope? {:scope "private"} :both true)))
    (is (false? (filters/matches-scope? {:scope "work"} :both true))))

  (testing "strict mode - nil scope defaults to both and only matches :both"
    (is (true? (filters/matches-scope? {} :both true)))
    (is (false? (filters/matches-scope? {} :private true)))
    (is (false? (filters/matches-scope? {} :work true)))))
