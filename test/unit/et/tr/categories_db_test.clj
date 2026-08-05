(ns et.tr.categories-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db.category :as db.category]
            [et.tr.db.user :as db.user]
            [et.tr.db.task :as db.task]
            [et.tr.db.resource :as db.resource]
            [et.tr.db.meet :as db.meet]
            [et.tr.db.issue :as db.issue]
            [et.tr.db.category-rule :as db.category-rule]
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
    (db.category/delete-category *ds* *user-id* (:id person) "person")
    (is (= 0 (count-join-rows :task_categories)))))

(deftest delete-category-cleans-up-resource-categories-test
  (let [resource (db.resource/add-resource *ds* *user-id* "My resource" "https://example.com" "both")
        person (db.category/add-person *ds* *user-id* "Alice")]
    (db.resource/categorize-resource *ds* *user-id* (:id resource) "person" (:id person))
    (is (= 1 (count-join-rows :resource_categories)))
    (db.category/delete-category *ds* *user-id* (:id person) "person")
    (is (= 0 (count-join-rows :resource_categories)))))

(deftest delete-category-cleans-up-meet-categories-test
  (let [meet (db.meet/add-meet *ds* *user-id* "My meet")
        person (db.category/add-person *ds* *user-id* "Alice")]
    (db.meet/categorize-meet *ds* *user-id* (:id meet) "person" (:id person))
    (is (= 1 (count-join-rows :meet_categories)))
    (db.category/delete-category *ds* *user-id* (:id person) "person")
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

;; ---------------------------------------------------------------------------
;; Changing an item's Group
;;
;; The whole reason the four category tables were unified into one: a Group is a
;; column value, so a move is an UPDATE and the row keeps its id — and with the
;; id, everything hanging off it. These tests exist to prove exactly that, and
;; each one names a thing that must survive rather than asserting "it moved".

(defn- join-rows [table]
  (jdbc/execute! (:conn *ds*)
    (sql/format {:select [:*] :from [table]})
    {:builder-fn rs/as-unqualified-maps}))

(defn- category-row [id]
  (jdbc/execute-one! (:conn *ds*)
    (sql/format {:select [:*] :from [:categories] :where [:= :id id]})
    {:builder-fn rs/as-unqualified-maps}))

(def ^:private all-join-tables
  [:task_categories :issue_categories :resource_categories :meet_categories
   :meeting_series_categories :recurring_task_categories
   :journal_categories :journal_entry_categories])

(defn- mirror-disagreements
  "Every join row whose category_type differs from the categories row it names,
  across all eight join tables. The invariant the denormalised mirror has to
  keep; anything in here is drift."
  []
  (vec (for [table all-join-tables
             row (jdbc/execute! (:conn *ds*)
                   (sql/format {:select [:j.category_type :j.category_id
                                         [:c.category_type :cat_type]]
                                :from [[table :j]]
                                :join [[:categories :c] [:= :c.id :j.category_id]]
                                :where [:<> :j.category_type :c.category_type]})
                   {:builder-fn rs/as-unqualified-maps})]
         (assoc row :table table))))

(deftest change-group-keeps-the-id-and-every-field
  (let [project (db.category/add-project *ds* *user-id* "Renovations")
        id (:id project)]
    (db.category/update-project *ds* *user-id* id "Renovations" "the description" "tag1 tag2" "RN")
    (db.category/set-project-field *ds* *user-id* id :scope "work")
    (let [before (category-row id)
          moved (db.category/set-category-group *ds* *user-id* id "workstream")
          after (category-row id)]
      (testing "the row is the same row"
        (is (= id (:id moved)))
        (is (= id (:id after))))
      (testing "and it is now a workstream"
        (is (= "workstream" (:category_type after)))
        (is (= "workstream" (:category_type moved))))
      (testing "name, description, tags, badge title and scope all survive"
        (is (= "Renovations" (:name after)))
        (is (= "the description" (:description after)))
        (is (= "tag1 tag2" (:tags after)))
        (is (= "RN" (:badge_title after)))
        (is (= "work" (:scope after)))
        (is (= (select-keys before [:name :description :tags :badge_title :scope :user_id])
               (select-keys after [:name :description :tags :badge_title :scope :user_id]))))
      (testing "it is listed under its new group and no longer under the old one"
        (is (= ["Renovations"] (map :name (db.category/list-workstreams *ds* *user-id*))))
        (is (= [] (map :name (db.category/list-projects *ds* *user-id*))))))))

(deftest change-group-keeps-associations-in-every-entity-type
  (let [project (db.category/add-project *ds* *user-id* "Renovations")
        id (:id project)
        task (db.task/add-task *ds* *user-id* "Fix the roof")
        issue (db.issue/add-issue *ds* *user-id* "Roof leaks")
        resource (db.resource/add-resource *ds* *user-id* "Roofing guide" "https://example.com" "both")
        meet (db.meet/add-meet *ds* *user-id* "Roofer visit")]
    (db.task/categorize-task *ds* *user-id* (:id task) "project" id)
    (db.issue/categorize-issue *ds* *user-id* (:id issue) "project" id)
    (db.resource/categorize-resource *ds* *user-id* (:id resource) "project" id)
    (db.meet/categorize-meet *ds* *user-id* (:id meet) "project" id)

    (db.category/set-category-group *ds* *user-id* id "workstream")

    (testing "the task still carries it, now under :workstreams"
      (let [t (first (db.task/list-tasks *ds* *user-id*))]
        (is (= ["Renovations"] (map :name (:workstreams t))))
        (is (= [] (:projects t)))))
    (testing "so does the issue"
      (let [i (first (db.issue/list-issues *ds* *user-id*))]
        (is (= ["Renovations"] (map :name (:workstreams i))))))
    (testing "so does the resource"
      (let [r (first (db.resource/list-resources *ds* *user-id*))]
        (is (= ["Renovations"] (map :name (:workstreams r))))))
    (testing "so does the meet"
      (let [m (first (db.meet/list-meets *ds* *user-id*))]
        (is (= ["Renovations"] (map :name (:workstreams m))))))
    (testing "no association was dropped or duplicated"
      (is (= 1 (count-join-rows :task_categories)))
      (is (= 1 (count-join-rows :issue_categories)))
      (is (= 1 (count-join-rows :resource_categories)))
      (is (= 1 (count-join-rows :meet_categories))))))

(deftest change-group-updates-the-mirror-in-every-join-table
  (let [project (db.category/add-project *ds* *user-id* "Renovations")
        id (:id project)
        task (db.task/add-task *ds* *user-id* "Fix the roof")
        issue (db.issue/add-issue *ds* *user-id* "Roof leaks")]
    (db.task/categorize-task *ds* *user-id* (:id task) "project" id)
    (db.issue/categorize-issue *ds* *user-id* (:id issue) "project" id)
    (is (empty? (mirror-disagreements)) "precondition: the mirror agrees before the move")

    (db.category/set-category-group *ds* *user-id* id "asset")

    (testing "the mirror never disagrees with categories.category_type"
      (is (empty? (mirror-disagreements))))
    (testing "and it holds the NEW type, not the old one"
      (is (= ["asset"] (distinct (map :category_type (join-rows :task_categories)))))
      (is (= ["asset"] (distinct (map :category_type (join-rows :issue_categories))))))))

(deftest change-group-moves-the-item-to-the-end-of-the-destination-order
  (let [a (db.category/add-workstream *ds* *user-id* "WS A")
        b (db.category/add-workstream *ds* *user-id* "WS B")
        project (db.category/add-project *ds* *user-id* "Renovations")]
    (testing "the destination group already has an order"
      (is (= 1.0 (:sort_order a)))
      (is (= 2.0 (:sort_order b))))
    (testing "the mover's own sort_order means nothing in the group it joins"
      (is (= 1.0 (:sort_order project))))
    (let [moved (db.category/set-category-group *ds* *user-id* (:id project) "workstream")]
      (testing "so it is appended, exactly where a newly created item would land"
        (is (= 3.0 (:sort_order moved))))
      (testing "and nothing else in the destination moved"
        (is (= {"WS A" 1.0 "WS B" 2.0 "Renovations" 3.0}
               (into {} (map (juxt :name :sort_order))
                     (db.category/list-workstreams *ds* *user-id*))))))))

(deftest change-group-follows-the-category-rules
  (testing "a rule names its endpoints by (type, id) too, so it has to move with
            the item or it would silently stop firing"
    (let [project (db.category/add-project *ds* *user-id* "Renovations")
          person (db.category/add-person *ds* *user-id* "Roofer")
          task (db.task/add-task *ds* *user-id* "Fix the roof")]
      (db.category-rule/add-rule *ds* *user-id* "project" (:id project) "person" (:id person))
      (db.category/set-category-group *ds* *user-id* (:id project) "workstream")
      (testing "the rule now names the workstream"
        (let [rule (first (db.category-rule/list-rules *ds* *user-id*))]
          (is (= "workstream" (:source_type rule)))
          (is (= (:id project) (:source_id rule)))
          (is (= "Renovations" (:source_name rule)))))
      (testing "and it still fires: categorizing with it pulls the target in"
        (db.task/categorize-task *ds* *user-id* (:id task) "workstream" (:id project))
        (let [t (first (db.task/list-tasks *ds* *user-id*))]
          (is (= ["Renovations"] (map :name (:workstreams t))))
          (is (= ["Roofer"] (map :name (:people t)))))))))

(deftest change-group-to-the-same-group-is-a-no-op
  (let [project (db.category/add-project *ds* *user-id* "Renovations")
        task (db.task/add-task *ds* *user-id* "Fix the roof")]
    (db.task/categorize-task *ds* *user-id* (:id task) "project" (:id project))
    (let [before (category-row (:id project))
          result (db.category/set-category-group *ds* *user-id* (:id project) "project")]
      (is (some? result) "a no-op move still answers with the row")
      (is (= "project" (:category_type result)))
      (testing "nothing changed, not even sort_order or modified_at"
        (is (= before (category-row (:id project)))))
      (is (= 1 (count-join-rows :task_categories))))))

(deftest change-group-refuses-what-is-not-the-callers
  (let [other-id (:id (db.user/create-user *ds* "someone-else" "pw"))
        project (db.category/add-project *ds* *user-id* "Renovations")]
    (testing "another user's category is not found, and is left alone"
      (is (nil? (db.category/set-category-group *ds* other-id (:id project) "workstream")))
      (is (= "project" (:category_type (category-row (:id project))))))
    (testing "a category that does not exist is not found"
      (is (nil? (db.category/set-category-group *ds* *user-id* 999999 "workstream"))))))

(deftest change-group-rejects-an-unknown-group
  (let [project (db.category/add-project *ds* *user-id* "Renovations")]
    (is (thrown? clojure.lang.ExceptionInfo
                 (db.category/set-category-group *ds* *user-id* (:id project) "nonsense")))
    (is (= "project" (:category_type (category-row (:id project)))))))

;; A reorder addressed to one Group must not move another Group's rows. That
;; used to be asserted here, against db.category/reorder-category — but that
;; call reaches db/write-order!, a single-row `UPDATE ... WHERE id = ?`, so
;; "no project's position moved" could not fail whatever the handler did. The
;; assertion needs the handler, which is the only layer that knows which group
;; the request was addressed to; it now lives in
;; et.tr.category-reorder-integration-test, where it has been watched fail.

(deftest names-are-unique-per-user-across-all-groups
  (testing "one table, one UNIQUE(name, user_id) — so a name taken by any group
            is taken for every group. This is a real change from four tables and
            is asserted rather than discovered."
    (db.category/add-person *ds* *user-id* "Ambiguous")
    (is (thrown? Exception (db.category/add-project *ds* *user-id* "Ambiguous")))
    (testing "but another user may still use it"
      (let [other-id (:id (db.user/create-user *ds* "another" "pw"))]
        (is (some? (db.category/add-project *ds* other-id "Ambiguous")))))))
