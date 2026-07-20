(ns et.tr.category-rules-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db.category :as db.category]
            [et.tr.db.category-rule :as db.category-rule]
            [et.tr.db.task :as db.task]
            [et.tr.db.resource :as db.resource]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(defn- task-category-set [task-id kind]
  (set (map :name (get (db.task/get-task *ds* *user-id* task-id) kind))))

(deftest rule-crud-test
  (testing "add, list and delete a rule"
    (let [alice (db.category/add-person *ds* *user-id* "Alice")
          alpha (db.category/add-project *ds* *user-id* "Alpha")]
      (db.category-rule/add-rule *ds* *user-id* "person" (:id alice) "project" (:id alpha))
      (let [rules (db.category-rule/list-rules *ds* *user-id*)]
        (is (= 1 (count rules)))
        (is (= {:source_type "person" :source_id (:id alice) :source_name "Alice"
                :target_type "project" :target_id (:id alpha) :target_name "Alpha"}
               (dissoc (first rules) :id)))
        (db.category-rule/delete-rule *ds* *user-id* (:id (first rules)))
        (is (empty? (db.category-rule/list-rules *ds* *user-id*)))))))

(deftest rule-rejects-self-pair-and-duplicates-test
  (testing "self-pairs are rejected and duplicates do not create extra rows"
    (let [alice (db.category/add-person *ds* *user-id* "Alice")
          alpha (db.category/add-project *ds* *user-id* "Alpha")]
      (is (nil? (db.category-rule/add-rule *ds* *user-id* "person" (:id alice) "person" (:id alice))))
      (db.category-rule/add-rule *ds* *user-id* "person" (:id alice) "project" (:id alpha))
      (db.category-rule/add-rule *ds* *user-id* "person" (:id alice) "project" (:id alpha))
      (is (= 1 (count (db.category-rule/list-rules *ds* *user-id*)))))))

(deftest single-rule-fires-on-categorization-test
  (testing "assigning the source also assigns the rule target"
    (let [task (db.task/add-task *ds* *user-id* "My task")
          alice (db.category/add-person *ds* *user-id* "Alice")
          alpha (db.category/add-project *ds* *user-id* "Alpha")]
      (db.category-rule/add-rule *ds* *user-id* "person" (:id alice) "project" (:id alpha))
      (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id alice))
      (is (= #{"Alice"} (task-category-set (:id task) :people)))
      (is (= #{"Alpha"} (task-category-set (:id task) :projects))))))

(deftest multi-target-source-test
  (testing "a source with multiple rules assigns every target"
    (let [task (db.task/add-task *ds* *user-id* "My task")
          alice (db.category/add-person *ds* *user-id* "Alice")
          alpha (db.category/add-project *ds* *user-id* "Alpha")
          launch (db.category/add-goal *ds* *user-id* "Launch")]
      (db.category-rule/add-rule *ds* *user-id* "person" (:id alice) "project" (:id alpha))
      (db.category-rule/add-rule *ds* *user-id* "person" (:id alice) "goal" (:id launch))
      (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id alice))
      (is (= #{"Alice"} (task-category-set (:id task) :people)))
      (is (= #{"Alpha"} (task-category-set (:id task) :projects)))
      (is (= #{"Launch"} (task-category-set (:id task) :goals))))))

(deftest transitive-chain-test
  (testing "rules follow chains: A->B, B->C means adding A adds B and C"
    (let [task (db.task/add-task *ds* *user-id* "My task")
          alice (db.category/add-person *ds* *user-id* "Alice")
          home (db.category/add-place *ds* *user-id* "Home")
          launch (db.category/add-goal *ds* *user-id* "Launch")]
      (db.category-rule/add-rule *ds* *user-id* "person" (:id alice) "place" (:id home))
      (db.category-rule/add-rule *ds* *user-id* "place" (:id home) "goal" (:id launch))
      (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id alice))
      (is (= #{"Alice"} (task-category-set (:id task) :people)))
      (is (= #{"Home"} (task-category-set (:id task) :places)))
      (is (= #{"Launch"} (task-category-set (:id task) :goals))))))

(deftest cycle-protection-test
  (testing "a cyclic rule set (A->B, B->A) resolves without looping"
    (let [task (db.task/add-task *ds* *user-id* "My task")
          alice (db.category/add-person *ds* *user-id* "Alice")
          bob (db.category/add-person *ds* *user-id* "Bob")]
      (db.category-rule/add-rule *ds* *user-id* "person" (:id alice) "person" (:id bob))
      (db.category-rule/add-rule *ds* *user-id* "person" (:id bob) "person" (:id alice))
      (is (= #{["person" (:id alice)] ["person" (:id bob)]}
             (set (db.category-rule/resolve-closure *ds* *user-id* [["person" (:id alice)]]))))
      (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id alice))
      (is (= #{"Alice" "Bob"} (task-category-set (:id task) :people))))))

(deftest no-duplicate-assignment-test
  (testing "already-assigned targets cause no duplicates and no errors"
    (let [task (db.task/add-task *ds* *user-id* "My task")
          alice (db.category/add-person *ds* *user-id* "Alice")
          alpha (db.category/add-project *ds* *user-id* "Alpha")]
      (db.category-rule/add-rule *ds* *user-id* "person" (:id alice) "project" (:id alpha))
      (db.task/categorize-task *ds* *user-id* (:id task) "project" (:id alpha))
      (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id alice))
      (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id alice))
      (is (= #{"Alice"} (task-category-set (:id task) :people)))
      (is (= #{"Alpha"} (task-category-set (:id task) :projects))))))

(deftest uncategorize-triggers-nothing-test
  (testing "removing the source does not cascade to rule targets"
    (let [task (db.task/add-task *ds* *user-id* "My task")
          alice (db.category/add-person *ds* *user-id* "Alice")
          alpha (db.category/add-project *ds* *user-id* "Alpha")]
      (db.category-rule/add-rule *ds* *user-id* "person" (:id alice) "project" (:id alpha))
      (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id alice))
      (db.task/uncategorize-task *ds* *user-id* (:id task) "person" (:id alice))
      (is (= #{} (task-category-set (:id task) :people)))
      (is (= #{"Alpha"} (task-category-set (:id task) :projects))))))

(deftest cross-type-rule-test
  (testing "a rule may point across category types (place -> goal)"
    (let [resource (db.resource/add-resource *ds* *user-id* "My resource" "" "both")
          home (db.category/add-place *ds* *user-id* "Home")
          launch (db.category/add-goal *ds* *user-id* "Launch")]
      (db.category-rule/add-rule *ds* *user-id* "place" (:id home) "goal" (:id launch))
      (db.resource/categorize-resource *ds* *user-id* (:id resource) "place" (:id home))
      (let [r (db.resource/get-resource *ds* *user-id* (:id resource))]
        (is (= #{"Launch"} (set (map :name (:goals r)))))))))

(deftest filter-resolve-closure-test
  (testing "resolve-closure returns the correct transitive closure for filters"
    (let [alice (db.category/add-person *ds* *user-id* "Alice")
          alpha (db.category/add-project *ds* *user-id* "Alpha")
          launch (db.category/add-goal *ds* *user-id* "Launch")
          bob (db.category/add-person *ds* *user-id* "Bob")]
      (db.category-rule/add-rule *ds* *user-id* "person" (:id alice) "project" (:id alpha))
      (db.category-rule/add-rule *ds* *user-id* "project" (:id alpha) "goal" (:id launch))
      (is (= #{["person" (:id alice)] ["project" (:id alpha)] ["goal" (:id launch)]}
             (set (db.category-rule/resolve-closure *ds* *user-id* [["person" (:id alice)]]))))
      (testing "a category with no rules resolves to only itself"
        (is (= #{["person" (:id bob)]}
               (set (db.category-rule/resolve-closure *ds* *user-id* [["person" (:id bob)]]))))))))

(deftest delete-category-cleans-up-rules-test
  (testing "deleting a category removes rules referencing it as source or target"
    (let [alice (db.category/add-person *ds* *user-id* "Alice")
          alpha (db.category/add-project *ds* *user-id* "Alpha")
          launch (db.category/add-goal *ds* *user-id* "Launch")]
      (db.category-rule/add-rule *ds* *user-id* "person" (:id alice) "project" (:id alpha))
      (db.category-rule/add-rule *ds* *user-id* "project" (:id alpha) "goal" (:id launch))
      (is (= 2 (count (db.category-rule/list-rules *ds* *user-id*))))
      (db.category/delete-category *ds* *user-id* (:id alpha) "project" "projects")
      (is (empty? (db.category-rule/list-rules *ds* *user-id*))))))
