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
