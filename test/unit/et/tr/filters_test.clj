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

(def ^:private plain {})
(def ^:private shift {:shift? true})
(def ^:private shift-alt {:shift? true :alt? true})

(def ^:private clean-slate
  {:negative-active? false :any-filters? false :type-filtered? false})

;; All 8 combinations, two of them unreachable in the app (a filter of the
;; badge's own type means there are active filters), so a gesture missing on one
;; of those two is a finding about the matrix, not about the app.
(def ^:private all-gates
  (for [negative-active? [false true]
        any-filters? [false true]
        type-filtered? [false true]]
    {:negative-active? negative-active?
     :any-filters? any-filters?
     :type-filtered? type-filtered?}))

(deftest badge-gesture-test
  (testing "clean slate: every gesture is open"
    (is (= :toggle (filters/badge-gesture plain clean-slate)))
    (is (= :exclude (filters/badge-gesture shift clean-slate)))
    (is (= :bypass (filters/badge-gesture shift-alt clean-slate))))

  (testing "shift+option is not swallowed by the negative-filter branch"
    (is (= :bypass (filters/badge-gesture shift-alt clean-slate)))
    (is (= :bypass (filters/badge-gesture
                    shift-alt
                    (assoc clean-slate :any-filters? true :type-filtered? true)))))

  (testing "shift+option survives a filter of the badge's own type, the plain click does not"
    (let [gate (assoc clean-slate :any-filters? true :type-filtered? true)]
      (is (nil? (filters/badge-gesture plain gate)))
      (is (= :bypass (filters/badge-gesture shift-alt gate)))))

  (testing "a filter of another type leaves the plain click open"
    (let [gate (assoc clean-slate :any-filters? true)]
      (is (= :toggle (filters/badge-gesture plain gate)))))

  (testing "shift alone is refused, not folded into the plain path, while a positive filter is up"
    (let [gate (assoc clean-slate :any-filters? true)]
      (is (nil? (filters/badge-gesture shift gate)))))

  (testing "a negative filter closes every path but adding another exclusion"
    (let [gate (assoc clean-slate :negative-active? true)]
      (is (nil? (filters/badge-gesture plain gate)))
      (is (nil? (filters/badge-gesture shift-alt gate)))
      (is (= :exclude (filters/badge-gesture shift gate)))))

  (testing "option alone is a plain click"
    (is (= :toggle (filters/badge-gesture {:alt? true} clean-slate))))

  ;; What makes the unconditional pointer cursor honest: there is no gate state
  ;; in which a badge has nothing at all to offer. Narrow one of the three
  ;; gestures and this is what says the cursor now promises too much.
  (testing "every gate state leaves at least one gesture open"
    (doseq [gate all-gates]
      (is (some #(filters/badge-gesture % gate) [shift-alt shift plain])
          (str "no gesture open for " gate)))))

(deftest badge-consumes-click?-test
  (testing "a click a gesture runs on stays on the badge"
    (is (true? (filters/badge-consumes-click? plain clean-slate)))
    (is (true? (filters/badge-consumes-click? shift clean-slate)))
    (is (true? (filters/badge-consumes-click? shift-alt clean-slate))))

  (testing "a shift-click refused because a positive filter is up stays on the badge"
    (let [gate (assoc clean-slate :any-filters? true)]
      (is (nil? (filters/badge-gesture shift gate)))
      (is (true? (filters/badge-consumes-click? shift gate)))))

  (testing "so does every click a negative filter refuses"
    (let [gate (assoc clean-slate :negative-active? true)]
      (is (nil? (filters/badge-gesture plain gate)))
      (is (true? (filters/badge-consumes-click? plain gate)))
      (is (nil? (filters/badge-gesture shift-alt gate)))
      (is (true? (filters/badge-consumes-click? shift-alt gate)))))

  (testing "with a filter of the badge's own type only Shift+Option keeps the click, the others reach the row they sit in as they did before the bypass"
    (let [gate (assoc clean-slate :any-filters? true :type-filtered? true)]
      (is (false? (filters/badge-consumes-click? plain gate)))
      (is (false? (filters/badge-consumes-click? {:alt? true} gate)))
      (is (false? (filters/badge-consumes-click? shift gate)))
      (is (true? (filters/badge-consumes-click? shift-alt gate))))))
