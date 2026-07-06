(ns et.tr.task-issue-relation-integration-test
  "HTTP-layer tests for the relation handler's task↔issue routing. A tsk↔iss
  relation is not a generic bidirectional relations row: the handler routes it
  to the task's issue_id belongs-to FK instead. These tests exercise POST/DELETE
  /api/relations and assert the FK is set/cleared and that no relations row is
  ever created for the pair."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.tr.db :as db]
            [et.tr.db.task :as db.task]
            [et.tr.db.issue :as db.issue]
            [et.tr.db.event :as db.event]
            [et.tr.integration-helpers :refer [with-integration-db POST-json DELETE-json
                                               *ds* *user-id*]]))

(use-fixtures :each with-integration-db)

(defn- task-issue-id [task-id]
  (:issue_id (jdbc/execute-one! (db/get-conn *ds*)
               (sql/format {:select [:issue_id] :from [:tasks] :where [:= :id task-id]})
               db/jdbc-opts)))

(defn- relations-row-count []
  (:c (jdbc/execute-one! (db/get-conn *ds*)
        (sql/format {:select [[[:count :*] :c]] :from [:relations]})
        db/jdbc-opts)))

(defn- seed! []
  (let [task (db.task/add-task *ds* *user-id* "Reproduce the bug")
        issue (db.issue/add-issue *ds* *user-id* "Flaky login")]
    {:task-id (:id task) :issue-id (:id issue)}))

(deftest link-task-to-issue-routes-to-fk-not-relations-row
  (testing "POST /api/relations tsk→iss sets the task's issue_id FK, no relations row"
    (let [{:keys [task-id issue-id]} (seed!)
          resp (POST-json "/api/relations" {:source-type "tsk" :source-id task-id
                                            :target-type "iss" :target-id issue-id})]
      (is (= 201 (:status resp)))
      (is (= issue-id (task-issue-id task-id)))
      (is (= 0 (relations-row-count)) "no generic relations row should be created"))))

(deftest link-issue-to-task-is-order-independent
  (testing "POST /api/relations iss→tsk also routes to the FK, no relations row"
    (let [{:keys [task-id issue-id]} (seed!)
          resp (POST-json "/api/relations" {:source-type "iss" :source-id issue-id
                                            :target-type "tsk" :target-id task-id})]
      (is (= 201 (:status resp)))
      (is (= issue-id (task-issue-id task-id)))
      (is (= 0 (relations-row-count))))))

(deftest unlink-task-from-issue-clears-fk
  (testing "DELETE /api/relations tsk→iss clears the task's issue_id FK"
    (let [{:keys [task-id issue-id]} (seed!)]
      (POST-json "/api/relations" {:source-type "tsk" :source-id task-id
                                   :target-type "iss" :target-id issue-id})
      (is (= issue-id (task-issue-id task-id)))
      (let [resp (DELETE-json "/api/relations" {:source-type "tsk" :source-id task-id
                                                :target-type "iss" :target-id issue-id})]
        (is (= 200 (:status resp)))
        (is (nil? (task-issue-id task-id)))
        (is (= 0 (relations-row-count)) "still no generic relations row")))))

(deftest reassigning-task-audits-both-unlink-and-link
  (testing "linking a task already belonging to another issue records the implicit unlink"
    (let [task (db.task/add-task *ds* *user-id* "Shared task")
          issue-a (db.issue/add-issue *ds* *user-id* "Issue A")
          issue-b (db.issue/add-issue *ds* *user-id* "Issue B")
          task-id (:id task)]
      (POST-json "/api/relations" {:source-type "tsk" :source-id task-id
                                   :target-type "iss" :target-id (:id issue-a)})
      (POST-json "/api/relations" {:source-type "tsk" :source-id task-id
                                   :target-type "iss" :target-id (:id issue-b)})
      (is (= (:id issue-b) (task-issue-id task-id)) "task now belongs to B")
      (is (= 0 (relations-row-count)))
      (let [events (db.event/list-events-for-user *ds* *user-id*)
            rel-events (filter #(= "relation" (:entity_type %)) events)
            unlinks (filter #(= "relation-delete" (:action %)) rel-events)
            adds (filter #(= "relation-add" (:action %)) rel-events)]
        (is (= 2 (count adds)) "one relation-add per link")
        (is (= 1 (count unlinks)) "reassignment records exactly one implicit unlink")
        (is (= (:id issue-a) (get-in (first unlinks) [:payload :target :id]))
            "the unlink targets the displaced issue A")))))

(deftest relinking-to-same-issue-records-no-duplicate-add
  (testing "an idempotent re-link to the issue the task already belongs to adds no audit event"
    (let [{:keys [task-id issue-id]} (seed!)]
      (POST-json "/api/relations" {:source-type "tsk" :source-id task-id
                                   :target-type "iss" :target-id issue-id})
      (let [resp (POST-json "/api/relations" {:source-type "tsk" :source-id task-id
                                              :target-type "iss" :target-id issue-id})]
        (is (= 201 (:status resp)) "the idempotent re-link still succeeds")
        (is (= issue-id (task-issue-id task-id)) "task still belongs to the issue"))
      (let [events (db.event/list-events-for-user *ds* *user-id*)
            adds (filter #(and (= "relation" (:entity_type %)) (= "relation-add" (:action %))) events)]
        (is (= 1 (count adds)) "only the first link is audited; the re-link is a no-op")))))

(deftest create-task-for-issue-returns-fresh-modified-at
  (testing "the 201 body carries the current DB modified_at, not the pre-link value"
    (let [issue (db.issue/add-issue *ds* *user-id* "Squeaky door" "both")
          resp (POST-json (str "/api/issues/" (:id issue) "/create-task") {})
          task-id (:id (:body resp))
          fresh (db.task/get-task *ds* *user-id* task-id)]
      (is (= 201 (:status resp)))
      (is (= (:modified_at fresh) (:modified_at (:body resp)))
          "response reflects the row as it stands after the FK was set"))))

(deftest create-task-for-issue-sets-fk-and-inherits-title
  (testing "POST /api/issues/:id/create-task creates a task belonging to the issue"
    (let [issue (db.issue/add-issue *ds* *user-id* "Leaky roof" "work")
          resp (POST-json (str "/api/issues/" (:id issue) "/create-task") {})]
      (is (= 201 (:status resp)))
      (is (= (:id issue) (:issue_id (:body resp))) "new task's issue_id FK points at the issue")
      (is (= "Leaky roof" (:title (:body resp))) "task inherits the issue title")
      (is (= "work" (:scope (:body resp))) "task inherits the issue scope")
      (is (= (:id issue) (task-issue-id (:id (:body resp)))) "FK persisted in the DB")
      (is (= 0 (relations-row-count)) "no generic relations row is created"))))

(deftest create-task-for-missing-issue-is-404
  (testing "POST /api/issues/:id/create-task returns 404 for an unknown issue"
    (let [resp (POST-json "/api/issues/999999/create-task" {})]
      (is (= 404 (:status resp))))))
