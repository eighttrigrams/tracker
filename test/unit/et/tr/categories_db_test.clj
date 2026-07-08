(ns et.tr.categories-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db.category :as db.category]
            [et.tr.db.user :as db.user]
            [et.tr.db.task :as db.task]
            [et.tr.db.resource :as db.resource]
            [et.tr.db.meet :as db.meet]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(deftest people-crud-test
  (testing "add and list people"
    (db.category/add-person *ds* *user-id* "Alice")
    (db.category/add-person *ds* *user-id* "Bob")
    (let [people (db.category/list-people *ds* *user-id*)]
      (is (= 2 (count people)))
      (is (= ["Alice" "Bob"] (map :name people))))))

(deftest places-crud-test
  (testing "add and list places"
    (db.category/add-place *ds* *user-id* "Home")
    (db.category/add-place *ds* *user-id* "Work")
    (let [places (db.category/list-places *ds* *user-id*)]
      (is (= 2 (count places)))
      (is (= ["Home" "Work"] (map :name places))))))

(deftest projects-crud-test
  (testing "add and list projects"
    (db.category/add-project *ds* *user-id* "Alpha")
    (db.category/add-project *ds* *user-id* "Beta")
    (let [projects (db.category/list-projects *ds* *user-id*)]
      (is (= 2 (count projects)))
      (is (= ["Alpha" "Beta"] (map :name projects))))))

(deftest goals-crud-test
  (testing "add and list goals"
    (db.category/add-goal *ds* *user-id* "Learn Clojure")
    (db.category/add-goal *ds* *user-id* "Ship product")
    (let [goals (db.category/list-goals *ds* *user-id*)]
      (is (= 2 (count goals)))
      (is (= ["Learn Clojure" "Ship product"] (map :name goals))))))

(deftest category-name-unique-per-user-test
  (testing "different users can have categories with the same name"
    (let [user1 (db.user/create-user *ds* "user1" "pass1")
          user2 (db.user/create-user *ds* "user2" "pass2")
          user1-id (:id user1)
          user2-id (:id user2)]
      (db.category/add-person *ds* user1-id "John")
      (db.category/add-person *ds* user2-id "John")
      (db.category/add-place *ds* user1-id "Office")
      (db.category/add-place *ds* user2-id "Office")
      (db.category/add-project *ds* user1-id "Alpha")
      (db.category/add-project *ds* user2-id "Alpha")
      (db.category/add-goal *ds* user1-id "Launch")
      (db.category/add-goal *ds* user2-id "Launch")
      (is (= ["John"] (map :name (db.category/list-people *ds* user1-id))))
      (is (= ["John"] (map :name (db.category/list-people *ds* user2-id))))
      (is (= ["Office"] (map :name (db.category/list-places *ds* user1-id))))
      (is (= ["Office"] (map :name (db.category/list-places *ds* user2-id))))
      (is (= ["Alpha"] (map :name (db.category/list-projects *ds* user1-id))))
      (is (= ["Alpha"] (map :name (db.category/list-projects *ds* user2-id))))
      (is (= ["Launch"] (map :name (db.category/list-goals *ds* user1-id))))
      (is (= ["Launch"] (map :name (db.category/list-goals *ds* user2-id))))))

  (testing "same user cannot have duplicate category names"
    (let [user (db.user/create-user *ds* "testuser" "pass")
          user-id (:id user)]
      (db.category/add-person *ds* user-id "Alice")
      (is (thrown? Exception (db.category/add-person *ds* user-id "Alice"))))))

(defn- count-join-rows [table]
  (:cnt (jdbc/execute-one! (:conn *ds*)
          (sql/format {:select [[[:count :*] :cnt]] :from [table]})
          {:builder-fn rs/as-unqualified-maps})))

(deftest delete-category-cleans-up-task-categories-test
  (let [task (db.task/add-task *ds* *user-id* "My task")
        person (db.category/add-person *ds* *user-id* "Alice")]
    (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id person))
    (is (= 1 (count-join-rows :task_categories)))
    (db.category/delete-category *ds* *user-id* (:id person) "person" "people")
    (is (= 0 (count-join-rows :task_categories)))))

(deftest delete-category-cleans-up-resource-categories-test
  (let [resource (db.resource/add-resource *ds* *user-id* "My resource" "https://example.com" "both")
        person (db.category/add-person *ds* *user-id* "Alice")]
    (db.resource/categorize-resource *ds* *user-id* (:id resource) "person" (:id person))
    (is (= 1 (count-join-rows :resource_categories)))
    (db.category/delete-category *ds* *user-id* (:id person) "person" "people")
    (is (= 0 (count-join-rows :resource_categories)))))

(deftest delete-category-cleans-up-meet-categories-test
  (let [meet (db.meet/add-meet *ds* *user-id* "My meet")
        person (db.category/add-person *ds* *user-id* "Alice")]
    (db.meet/categorize-meet *ds* *user-id* (:id meet) "person" (:id person))
    (is (= 1 (count-join-rows :meet_categories)))
    (db.category/delete-category *ds* *user-id* (:id person) "person" "people")
    (is (= 0 (count-join-rows :meet_categories)))))

(deftest update-category-badge-title-test
  (let [person (db.category/add-person *ds* *user-id* "Alice Johnson")]
    (is (= "" (:badge_title person)))
    (let [updated (db.category/update-person *ds* *user-id* (:id person) "Alice Johnson" "" "" "AJ")]
      (is (= "AJ" (:badge_title updated))))
    (let [listed (first (db.category/list-people *ds* *user-id*))]
      (is (= "AJ" (:badge_title listed))))))

(deftest badge-title-appears-on-tasks-test
  (let [person (db.category/add-person *ds* *user-id* "Alice Johnson")
        _ (db.category/update-person *ds* *user-id* (:id person) "Alice Johnson" "" "" "AJ")
        task (db.task/add-task *ds* *user-id* "My task")]
    (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id person))
    (let [tasks (db.task/list-tasks *ds* *user-id*)
          t (first tasks)
          p (first (:people t))]
      (is (= "AJ" (:badge_title p))))))

(deftest list-people-search-test
  (let [alice (db.category/add-person *ds* *user-id* "Alice Johnson")
        bob   (db.category/add-person *ds* *user-id* "Bob")
        carol (db.category/add-person *ds* *user-id* "Carol")]
    (db.category/update-person *ds* *user-id* (:id alice) "Alice Johnson" "" "manager" "AJ")
    (db.category/update-person *ds* *user-id* (:id bob)   "Bob"           "" ""        "BX")
    (db.category/update-person *ds* *user-id* (:id carol) "Carol"         "" "manager" "")
    (testing "search by name"
      (is (= ["Alice Johnson"]
             (map :name (db.category/list-people *ds* *user-id* {:search-term "alice"})))))
    (testing "search by badge_title"
      (is (= ["Bob"]
             (map :name (db.category/list-people *ds* *user-id* {:search-term "BX"})))))
    (testing "search by tags"
      (is (= #{"Alice Johnson" "Carol"}
             (set (map :name (db.category/list-people *ds* *user-id* {:search-term "manager"}))))))
    (testing "blank search term returns all"
      (is (= 3 (count (db.category/list-people *ds* *user-id* {:search-term ""})))))
    (testing "no opts behaves like no filter (backward compatible)"
      (is (= 3 (count (db.category/list-people *ds* *user-id*)))))))

(deftest category-scope-defaults-to-both-test
  (testing "a freshly added category defaults to scope both"
    (let [person (db.category/add-person *ds* *user-id* "Alice")]
      (is (= "both" (:scope person)))
      (is (= "both" (:scope (first (db.category/list-people *ds* *user-id*))))))))

(deftest set-category-scope-round-trips-test
  (testing "set-person-field :scope persists and appears in listings"
    (let [person (db.category/add-person *ds* *user-id* "Alice")
          result (db.category/set-person-field *ds* *user-id* (:id person) :scope "work")]
      (is (= "work" (:scope result)))
      (is (= "work" (:scope (first (db.category/list-people *ds* *user-id*)))))))
  (testing "an invalid scope is normalized to both"
    (let [place (db.category/add-place *ds* *user-id* "Home")
          result (db.category/set-place-field *ds* *user-id* (:id place) :scope "bogus")]
      (is (= "both" (:scope result))))))

(deftest list-category-filters-by-scope-test
  (let [alice (db.category/add-person *ds* *user-id* "Alice")
        bob   (db.category/add-person *ds* *user-id* "Bob")
        carol (db.category/add-person *ds* *user-id* "Carol")]
    (db.category/set-person-field *ds* *user-id* (:id alice) :scope "private")
    (db.category/set-person-field *ds* *user-id* (:id bob) :scope "work")
    ;; carol stays "both"
    (testing "context work returns work + both categories"
      (is (= #{"Bob" "Carol"}
             (set (map :name (db.category/list-people *ds* *user-id* {:context "work"}))))))
    (testing "context private returns private + both categories"
      (is (= #{"Alice" "Carol"}
             (set (map :name (db.category/list-people *ds* *user-id* {:context "private"}))))))
    (testing "strict work returns only work-scoped categories"
      (is (= #{"Bob"}
             (set (map :name (db.category/list-people *ds* *user-id* {:context "work" :strict true}))))))
    (testing "strict private returns only private-scoped categories"
      (is (= #{"Alice"}
             (set (map :name (db.category/list-people *ds* *user-id* {:context "private" :strict true}))))))
    (testing "no context returns all categories"
      (is (= 3 (count (db.category/list-people *ds* *user-id*)))))
    (testing "context both (non-strict) returns all categories"
      (is (= 3 (count (db.category/list-people *ds* *user-id* {:context "both"})))))))

(deftest card-badges-filtered-by-scope-test
  (let [task (db.task/add-task *ds* *user-id* "Fix roof")
        boss (db.category/add-person *ds* *user-id* "Boss")
        mum  (db.category/add-person *ds* *user-id* "Mum")
        sam  (db.category/add-person *ds* *user-id* "Sam")]
    (db.category/set-person-field *ds* *user-id* (:id boss) :scope "work")
    (db.category/set-person-field *ds* *user-id* (:id mum) :scope "private")
    ;; Sam stays "both".
    (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id boss))
    (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id mum))
    (db.task/categorize-task *ds* *user-id* (:id task) "person" (:id sam))
    (testing "work scope drops the private-only badge, keeps work + both"
      (let [t (first (db.task/list-tasks *ds* *user-id* :recent {:context "work"}))]
        (is (= #{"Boss" "Sam"} (set (map :name (:people t)))))))
    (testing "private scope drops the work-only badge, keeps private + both"
      (let [t (first (db.task/list-tasks *ds* *user-id* :recent {:context "private"}))]
        (is (= #{"Mum" "Sam"} (set (map :name (:people t)))))))
    (testing "no scope keeps all badges"
      (let [t (first (db.task/list-tasks *ds* *user-id* :recent {}))]
        (is (= #{"Boss" "Mum" "Sam"} (set (map :name (:people t)))))))
    (testing "strict work scope keeps only the work-scoped badge"
      ;; the task itself must be in scope under strict work to be listed
      (db.task/set-task-field *ds* *user-id* (:id task) :scope "work")
      (let [t (first (db.task/list-tasks *ds* *user-id* :recent {:context "work" :strict true}))]
        (is (= #{"Boss"} (set (map :name (:people t)))))))))
