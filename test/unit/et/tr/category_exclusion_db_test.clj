(ns et.tr.category-exclusion-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db.category :as db.category]
            [et.tr.db.category-rule :as db.category-rule]
            [et.tr.db.task :as db.task]
            [et.tr.db.issue :as db.issue]
            [et.tr.db.resource :as db.resource]
            [et.tr.db.journal :as db.journal]
            [et.tr.db.journal-entry :as db.journal-entry]
            [et.tr.db.meeting-series :as db.meeting-series]
            [et.tr.db.recurring-task :as db.recurring-task]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(defn- task-titles [excluded]
  (set (map :title (db.task/list-tasks *ds* *user-id* :recent {:excluded-categories excluded}))))

(deftest excludes-the-seed-category-test
  (testing "an item carrying the excluded category is hidden, others stay"
    (let [alpha (db.category/add-project *ds* *user-id* "Alpha")
          beta (db.category/add-project *ds* *user-id* "Beta")
          t1 (db.task/add-task *ds* *user-id* "Alpha task")
          t2 (db.task/add-task *ds* *user-id* "Beta task")]
      (db.task/categorize-task *ds* *user-id* (:id t1) "project" (:id alpha))
      (db.task/categorize-task *ds* *user-id* (:id t2) "project" (:id beta))
      (is (= #{"Beta task"} (task-titles {:projects ["Alpha"]}))))))

(deftest excludes-through-a-rule-test
  (testing "excluding a rule's source also hides items carrying only its target"
    (let [plurama (db.category/add-project *ds* *user-id* "plurama")
          tracker (db.category/add-project *ds* *user-id* "tracker")
          other (db.category/add-project *ds* *user-id* "other")
          seeded (db.task/add-task *ds* *user-id* "Seeded")
          implied (db.task/add-task *ds* *user-id* "Implied")
          untouched (db.task/add-task *ds* *user-id* "Untouched")]
      (db.category-rule/add-rule *ds* *user-id* "project" (:id plurama) "project" (:id tracker))
      (db.task/categorize-task *ds* *user-id* (:id seeded) "project" (:id plurama))
      ;; Categorized with the rule target only, so it never carries the source:
      ;; a name-based NOT EXISTS on the seed would keep showing it.
      (db.task/categorize-task *ds* *user-id* (:id implied) "project" (:id tracker))
      (db.task/categorize-task *ds* *user-id* (:id untouched) "project" (:id other))
      (is (= #{"Untouched"} (task-titles {:projects ["plurama"]})))
      (testing "and the rule is directional: excluding the target keeps the source"
        (is (= #{"Untouched"} (task-titles {:projects ["tracker"]})))))))

(deftest excludes-along-a-transitive-chain-test
  (testing "A->B, B->C: excluding A hides items carrying B or C"
    (let [a (db.category/add-project *ds* *user-id* "A")
          b (db.category/add-project *ds* *user-id* "B")
          c (db.category/add-project *ds* *user-id* "C")
          d (db.category/add-project *ds* *user-id* "D")
          ta (db.task/add-task *ds* *user-id* "Task A")
          tb (db.task/add-task *ds* *user-id* "Task B")
          tc (db.task/add-task *ds* *user-id* "Task C")
          td (db.task/add-task *ds* *user-id* "Task D")]
      (db.category-rule/add-rule *ds* *user-id* "project" (:id a) "project" (:id b))
      (db.category-rule/add-rule *ds* *user-id* "project" (:id b) "project" (:id c))
      (db.task/categorize-task *ds* *user-id* (:id ta) "project" (:id a))
      (db.task/categorize-task *ds* *user-id* (:id tb) "project" (:id b))
      (db.task/categorize-task *ds* *user-id* (:id tc) "project" (:id c))
      (db.task/categorize-task *ds* *user-id* (:id td) "project" (:id d))
      (is (= #{"Task D"} (task-titles {:projects ["A"]}))))))

(deftest excludes-with-a-rule-cycle-test
  (testing "A->B, B->A terminates and hides both sides"
    (let [a (db.category/add-project *ds* *user-id* "A")
          b (db.category/add-project *ds* *user-id* "B")
          c (db.category/add-project *ds* *user-id* "C")
          ta (db.task/add-task *ds* *user-id* "Task A")
          tb (db.task/add-task *ds* *user-id* "Task B")
          tc (db.task/add-task *ds* *user-id* "Task C")]
      (db.category-rule/add-rule *ds* *user-id* "project" (:id a) "project" (:id b))
      (db.category-rule/add-rule *ds* *user-id* "project" (:id b) "project" (:id a))
      (db.task/categorize-task *ds* *user-id* (:id ta) "project" (:id a))
      (db.task/categorize-task *ds* *user-id* (:id tb) "project" (:id b))
      (db.task/categorize-task *ds* *user-id* (:id tc) "project" (:id c))
      (is (= #{"Task C"} (task-titles {:projects ["A"]}))))))

(deftest excludes-across-category-types-test
  (testing "a rule crossing types excludes on the target's own type"
    (let [alice (db.category/add-person *ds* *user-id* "Alice")
          alpha (db.category/add-project *ds* *user-id* "Alpha")
          launch (db.category/add-goal *ds* *user-id* "Launch")
          person-task (db.task/add-task *ds* *user-id* "Person task")
          project-task (db.task/add-task *ds* *user-id* "Project task")
          goal-task (db.task/add-task *ds* *user-id* "Goal task")
          _plain (db.task/add-task *ds* *user-id* "Plain task")]
      (db.category-rule/add-rule *ds* *user-id* "person" (:id alice) "project" (:id alpha))
      (db.category-rule/add-rule *ds* *user-id* "project" (:id alpha) "goal" (:id launch))
      (db.task/categorize-task *ds* *user-id* (:id person-task) "person" (:id alice))
      (db.task/categorize-task *ds* *user-id* (:id project-task) "project" (:id alpha))
      (db.task/categorize-task *ds* *user-id* (:id goal-task) "goal" (:id launch))
      (is (= #{"Plain task"} (task-titles {:people ["Alice"]}))))))

(deftest excludes-any-of-several-seeds-test
  (testing "an item is hidden when it carries any negative category"
    (let [alice (db.category/add-person *ds* *user-id* "Alice")
          home (db.category/add-place *ds* *user-id* "Home")
          t1 (db.task/add-task *ds* *user-id* "With Alice")
          t2 (db.task/add-task *ds* *user-id* "At home")
          _t3 (db.task/add-task *ds* *user-id* "Neither")]
      (db.task/categorize-task *ds* *user-id* (:id t1) "person" (:id alice))
      (db.task/categorize-task *ds* *user-id* (:id t2) "place" (:id home))
      (is (= #{"Neither"} (task-titles {:people ["Alice"] :places ["Home"]}))))
    (testing "and several seeds of one type accumulate"
      (let [alpha (db.category/add-project *ds* *user-id* "Alpha")
            beta (db.category/add-project *ds* *user-id* "Beta")
            t4 (db.task/add-task *ds* *user-id* "Alpha task")
            t5 (db.task/add-task *ds* *user-id* "Beta task")]
        (db.task/categorize-task *ds* *user-id* (:id t4) "project" (:id alpha))
        (db.task/categorize-task *ds* *user-id* (:id t5) "project" (:id beta))
        (is (not (contains? (task-titles {:projects ["Alpha" "Beta"]}) "Alpha task")))
        (is (not (contains? (task-titles {:projects ["Alpha" "Beta"]}) "Beta task")))))))

(deftest items-without-categories-are-never-excluded-test
  (testing "an uncategorized item survives every exclusion"
    (let [alice (db.category/add-person *ds* *user-id* "Alice")
          _home (db.category/add-place *ds* *user-id* "Home")
          _alpha (db.category/add-project *ds* *user-id* "Alpha")
          _launch (db.category/add-goal *ds* *user-id* "Launch")
          categorized (db.task/add-task *ds* *user-id* "Categorized")
          _plain (db.task/add-task *ds* *user-id* "Plain")]
      (db.task/categorize-task *ds* *user-id* (:id categorized) "person" (:id alice))
      (is (= #{"Plain"}
             (task-titles {:people ["Alice"] :places ["Home"]
                           :projects ["Alpha"] :goals ["Launch"]}))))))

(deftest unknown-and-empty-seeds-exclude-nothing-test
  (testing "names that resolve to no category leave the list untouched"
    (let [alpha (db.category/add-project *ds* *user-id* "Alpha")
          t1 (db.task/add-task *ds* *user-id* "Alpha task")]
      (db.task/categorize-task *ds* *user-id* (:id t1) "project" (:id alpha))
      (is (= #{"Alpha task"} (task-titles {:projects ["NonExistent"]})))
      (is (= #{"Alpha task"} (task-titles {:projects []})))
      (is (= #{"Alpha task"} (task-titles nil))))))

(deftest exclusion-applies-to-other-entity-types-test
  (testing "issues use their own join table and honour rules too"
    (let [plurama (db.category/add-project *ds* *user-id* "plurama")
          tracker (db.category/add-project *ds* *user-id* "tracker")
          seeded (db.issue/add-issue *ds* *user-id* "Seeded issue")
          implied (db.issue/add-issue *ds* *user-id* "Implied issue")
          _plain (db.issue/add-issue *ds* *user-id* "Plain issue")]
      (db.category-rule/add-rule *ds* *user-id* "project" (:id plurama) "project" (:id tracker))
      (db.issue/categorize-issue *ds* *user-id* (:id seeded) "project" (:id plurama))
      (db.issue/categorize-issue *ds* *user-id* (:id implied) "project" (:id tracker))
      (is (= #{"Plain issue"}
             (set (map :title (db.issue/list-issues *ds* *user-id*
                                {:excluded-categories {:projects ["plurama"]}}))))))))

(def ^:private remaining-wirings
  "The list fns whose exclusion clause nothing else covers. Each one wires its
  own join table into the same shape, so the risk is a wrong or dropped clause
  in a single entity type — hence one case per type rather than one for all."
  [{:label "resources"
    :add #(db.resource/add-resource *ds* *user-id* % nil "both")
    :categorize #(db.resource/categorize-resource *ds* *user-id* %1 %2 %3)
    :titles #(set (map :title (db.resource/list-resources *ds* *user-id* {:excluded-categories %})))}
   {:label "journals"
    :add #(db.journal/add-journal *ds* *user-id* %)
    :categorize #(db.journal/categorize-journal *ds* *user-id* %1 %2 %3)
    :titles #(set (map :title (db.journal/list-journals *ds* *user-id* {:excluded-categories %})))}
   {:label "journal entries"
    :add #(db.journal-entry/add-journal-entry *ds* *user-id* % "both")
    :categorize #(db.journal-entry/categorize-journal-entry *ds* *user-id* %1 %2 %3)
    :titles #(set (map :title (db.journal-entry/list-journal-entries *ds* *user-id* {:excluded-categories %})))}
   {:label "meeting series"
    :add #(db.meeting-series/add-meeting-series *ds* *user-id* %)
    :categorize #(db.meeting-series/categorize-meeting-series *ds* *user-id* %1 %2 %3)
    :titles #(set (map :title (db.meeting-series/list-meeting-series *ds* *user-id* {:excluded-categories %})))}
   {:label "recurring tasks"
    :add #(db.recurring-task/add-recurring-task *ds* *user-id* %)
    :categorize #(db.recurring-task/categorize-recurring-task *ds* *user-id* %1 %2 %3)
    :titles #(set (map :title (db.recurring-task/list-recurring-tasks *ds* *user-id* {:excluded-categories %})))}])

(deftest exclusion-is-wired-for-every-list-fn-test
  (let [plurama (db.category/add-project *ds* *user-id* "plurama")
        tracker (db.category/add-project *ds* *user-id* "tracker")]
    (db.category-rule/add-rule *ds* *user-id* "project" (:id plurama) "project" (:id tracker))
    (doseq [{:keys [label add categorize titles]} remaining-wirings]
      (testing (str label " honour the exclusion, rules included")
        (let [seeded (add (str label " seeded"))
              implied (add (str label " implied"))
              plain (add (str label " plain"))]
          (categorize (:id seeded) "project" (:id plurama))
          (categorize (:id implied) "project" (:id tracker))
          (is (= #{(:title plain)} (titles {:projects ["plurama"]})))
          (is (= #{(:title seeded) (:title implied) (:title plain)} (titles nil))))))))
