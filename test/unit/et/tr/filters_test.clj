(ns et.tr.filters-test
  (:require [clojure.test :refer [deftest testing is]]
            [et.tr.filters :as filters]))

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

(deftest refocus-search-after-badge-click?-test
  (testing "a plain click that applies the positive filter takes the cursor back"
    (is (true? (filters/refocus-search-after-badge-click? plain clean-slate)))
    (is (true? (filters/refocus-search-after-badge-click? {:alt? true} clean-slate)))
    (is (true? (filters/refocus-search-after-badge-click?
                plain (assoc clean-slate :any-filters? true)))))

  (testing "the other two gestures do not — he asked for selecting a filter, not for excluding or bypassing"
    (is (false? (filters/refocus-search-after-badge-click? shift clean-slate)))
    (is (false? (filters/refocus-search-after-badge-click? shift-alt clean-slate))))

  ;; The case that would go unnoticed: nothing was selected, so there is nothing
  ;; to come back from, and a cursor that jumped anyway would look like the app
  ;; had done something.
  (testing "a refused click moves nothing"
    (let [type-filtered (assoc clean-slate :any-filters? true :type-filtered? true)
          negative (assoc clean-slate :negative-active? true)]
      (is (nil? (filters/badge-gesture plain type-filtered)))
      (is (false? (filters/refocus-search-after-badge-click? plain type-filtered)))
      (is (nil? (filters/badge-gesture plain negative)))
      (is (false? (filters/refocus-search-after-badge-click? plain negative)))
      (is (false? (filters/refocus-search-after-badge-click? shift-alt negative)))))

  (testing "over every gate state, exactly the :toggle clicks refocus"
    (doseq [gate all-gates
            modifiers [plain shift shift-alt {:alt? true}]]
      (is (= (= :toggle (filters/badge-gesture modifiers gate))
             (filters/refocus-search-after-badge-click? modifiers gate))
          (str "gate " gate " modifiers " modifiers)))))
