(ns et.tr.issue-convert-integration-test
  "HTTP-layer tests for POST /api/issues/:id/convert-to-task — the issue becoming
  the task and ceasing to exist: what carries over (content, categories,
  relations), what does not (the manual orderings), the two 409 guards and their
  no-op-ness, and the pair of audit events."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.tr.db :as db]
            [et.tr.db.event :as db.event]
            [et.tr.db.task :as db.task]
            [et.tr.integration-helpers :refer [with-integration-db *ds* *user-id*
                                               POST-json PUT-json GET-json]]))

(use-fixtures :each with-integration-db)

(defn- create-issue! [title]
  (:body (POST-json "/api/issues" {:title title})))

(defn- create-task! [title]
  (:body (POST-json "/api/tasks" {:title title})))

(defn- create-category! [group name]
  (:body (POST-json (str "/api/" group) {:name name})))

(defn- convert! [issue-id]
  (POST-json (str "/api/issues/" issue-id "/convert-to-task") {}))

(defn- issue-ids []
  (set (map :id (:body (GET-json "/api/issues?sortMode=recent")))))

(defn- task-titles []
  (set (map :title (:body (GET-json "/api/tasks?sort=recent")))))

(defn- relation-targets
  "What GET /api/relations/:type/:id says this item is related to, as
  #{[type id]} — read back through the API rather than the table, so a
  re-pointed row that no longer reads as a relation would fail here."
  [type id]
  (set (map (juxt :type :id) (:body (GET-json (str "/api/relations/" type "/" id))))))

(defn- row-count [table where]
  (:c (jdbc/execute-one! (db/get-conn *ds*)
        (sql/format {:select [[[:count :*] :c]] :from [table] :where where})
        db/jdbc-opts)))

(deftest converts-an-issue-with-no-tasks
  (testing "the task carries the issue's content and every category, and the issue is gone"
    (let [issue (create-issue! "Roof needs doing")
          issue-id (:id issue)]
      (PUT-json (str "/api/issues/" issue-id) {:title "Roof needs doing"
                                               :description "Tiles, gutters, the lot"
                                               :tags "house roof"})
      (PUT-json (str "/api/issues/" issue-id "/scope") {:scope "private"})
      (PUT-json (str "/api/issues/" issue-id "/importance") {:importance "critical"})
      (PUT-json (str "/api/issues/" issue-id "/urgency") {:urgency "urgent"})
      ;; One category per Group, from the same list the server enumerates, so a
      ;; seventh Group is carried here without this test naming it.
      (let [categories (into {} (for [{:keys [type key]} db/category-groups
                                      :let [c (create-category! (name key) (str "Cat-" type))]]
                                  [key (do (POST-json (str "/api/issues/" issue-id "/categorize")
                                                      {:category-type type :category-id (:id c)})
                                           (:name c))]))
            {:keys [status body]} (convert! issue-id)]
        (is (= 201 status))
        (is (= "Roof needs doing" (:title body)))
        (is (= "Tiles, gutters, the lot" (:description body)))
        (is (= "house roof" (:tags body)))
        (is (= "private" (:scope body)))
        (is (= "critical" (:importance body)))
        (is (= "urgent" (:urgency body)))
        (testing "every Group came with it"
          (doseq [[key expected-name] categories]
            (is (= [expected-name] (mapv :name (get body key)))
                (str "category of group " key))))
        (testing "the issue no longer exists"
          (is (not (contains? (issue-ids) issue-id)))
          (is (= 404 (:status (GET-json (str "/api/issues/" issue-id)))))
          (is (zero? (row-count :issue_categories [:= :issue_id issue-id]))))
        (testing "the task is a real, fetchable task"
          (is (contains? (task-titles) "Roof needs doing")))))))

(deftest relations-survive-in-both-directions
  (testing "an issue that is a relation's source and another's target ends up with the task at both ends"
    ;; Deliberately a resource and a meet rather than two tasks: a task/issue
    ;; pair is not a relations row at all — add-relation-handler turns it into
    ;; the belongs-to link (tasks.issue_id) — and an issue holding one of those
    ;; is refused by the guard anyway.
    (let [issue-id (:id (create-issue! "Central matter"))
          resource-id (:id (:body (POST-json "/api/resources" {:title "Points at the issue"})))
          meet-id (:id (:body (POST-json "/api/meets" {:title "The issue points at it"})))]
      ;; add-relation writes both directions, so which item the request names as
      ;; source is what tells these two rows apart in the relations table.
      (POST-json "/api/relations" {:source-type "iss" :source-id issue-id
                                   :target-type "met" :target-id meet-id})
      (POST-json "/api/relations" {:source-type "res" :source-id resource-id
                                   :target-type "iss" :target-id issue-id})
      (let [{:keys [status body]} (convert! issue-id)
            new-task-id (:id body)]
        (is (= 201 status))
        (is (= #{["met" meet-id] ["res" resource-id]} (relation-targets "tsk" new-task-id)))
        (testing "and the items at the other ends now point at the task"
          (is (= #{["tsk" new-task-id]} (relation-targets "met" meet-id)))
          (is (= #{["tsk" new-task-id]} (relation-targets "res" resource-id))))
        (testing "nothing is left pointing at the issue"
          (is (zero? (row-count :relations [:or
                                            [:and [:= :source_type "iss"] [:= :source_id issue-id]]
                                            [:and [:= :target_type "iss"] [:= :target_id issue-id]]]))))))))

(deftest refuses-an-issue-with-an-undone-task
  (testing "409, and nothing happened"
    (let [issue-id (:id (create-issue! "Has open work"))
          _ (POST-json (str "/api/issues/" issue-id "/create-task") {:title "Open task"})
          before-tasks (count (:body (GET-json "/api/tasks?sort=recent")))
          {:keys [status body]} (convert! issue-id)]
      (is (= 409 status))
      (is (some? (:error body)))
      (is (contains? (issue-ids) issue-id) "the issue is still there")
      (is (= before-tasks (count (:body (GET-json "/api/tasks?sort=recent")))) "no task was created"))))

(deftest refuses-an-issue-whose-only-task-is-done
  (testing "the guard counts any associated task, not just the undone ones"
    (let [issue-id (:id (create-issue! "Work already finished"))
          task-id (:id (:body (POST-json (str "/api/issues/" issue-id "/create-task") {:title "Done task"})))]
      (PUT-json (str "/api/tasks/" task-id "/done") {:done true})
      (testing "set-issue-resolved's weaker rule now lets the issue resolve"
        (is (= 200 (:status (PUT-json (str "/api/issues/" issue-id "/resolved") {:resolved true}))))
        (PUT-json (str "/api/issues/" issue-id "/resolved") {:resolved false}))
      (let [{:keys [status]} (convert! issue-id)]
        (is (= 409 status))
        (is (contains? (issue-ids) issue-id))))))

(deftest refuses-a-resolved-issue
  (testing "409, like create-task, and the issue and its categories are untouched"
    (let [issue-id (:id (create-issue! "Settled"))
          category (create-category! "places" "Lagos")]
      (POST-json (str "/api/issues/" issue-id "/categorize")
                 {:category-type "place" :category-id (:id category)})
      (PUT-json (str "/api/issues/" issue-id "/resolved") {:resolved true})
      (let [{:keys [status body]} (convert! issue-id)]
        (is (= 409 status))
        (is (some? (:error body)))
        (is (= 200 (:status (GET-json (str "/api/issues/" issue-id)))))
        (is (= 1 (row-count :issue_categories [:= :issue_id issue-id]))
            "no category row moved")
        (is (zero? (row-count :task_categories [:= :category_id (:id category)])))))))

(deftest sort-order-is-not-copied
  (testing "the converted task sits where a new task sits, not at the issue's position"
    (let [;; Two tasks first, so the tasks list has an order of its own for the
          ;; newcomer to land on top of.
          _ (create-task! "First task")
          _ (create-task! "Second task")
          issue-id (:id (create-issue! "Bottom of the issues list"))]
      ;; Push the issue far down its own list; a copied sort_order would drop the
      ;; task to the bottom of the tasks list with it.
      (jdbc/execute-one! (db/get-conn *ds*)
        (sql/format {:update :issues :set {:sort_order 500.0} :where [:= :id issue-id]}))
      (let [task (:body (convert! issue-id))
            manual-order (map :title (:body (GET-json "/api/tasks?sort=manual")))]
        (is (not= 500.0 (:sort_order task)))
        (is (= "Bottom of the issues list" (first manual-order))
            "a new task goes to the top of the manual order")))))

(deftest an-urgent-issue-arrives-at-the-top-of-its-urgency-block
  (testing "the converted task is given a position in Urgent Matters, not the issue's"
    ;; Urgent Matters is an ordering context of its own and an item entering one is
    ;; given a concrete position there — the same rule place-in-urgent-list! follows
    ;; for a task that becomes urgent. Nothing asserted it before, so replacing the
    ;; hand-rolled minimum with db/top-of-order had no cover.
    (let [sitting (:id (create-task! "Already urgent"))
          _ (PUT-json (str "/api/tasks/" sitting "/urgency") {:urgency "urgent"})
          issue-id (:id (create-issue! "Urgent matter"))]
      (PUT-json (str "/api/issues/" issue-id "/urgency") {:urgency "urgent"})
      ;; Push the issue to the bottom of the Issues page's urgent order; a copied
      ;; position would put the task below the one already sitting there.
      (jdbc/execute-one! (db/get-conn *ds*)
        (sql/format {:update :issues :set {:sort_order_urgent 900.0} :where [:= :id issue-id]}))
      (let [task (:body (convert! issue-id))
            ;; list-urgent-tasks is the order Urgent Matters renders — one block per
            ;; urgency, by sort_order_urgent — so this reads the position the way the
            ;; page does rather than trusting the column.
            block (mapv :id (db.task/list-urgent-tasks *ds* *user-id* "urgent"))]
        (is (= "urgent" (:urgency task)))
        (is (not= 900.0 (:sort_order_urgent task)) "the issue's urgent position was not copied")
        (is (= [(:id task) sitting] block)
            "an item entering Urgent Matters goes to the top of its urgency's block")))))

(deftest another-users-issue-is-404
  (testing "404 rather than 409 — an issue that is not the caller's is not there at all"
    (let [other-user (:id (jdbc/execute-one! (db/get-conn *ds*)
                            (sql/format {:insert-into :users
                                         :values [{:username "someone-else" :password_hash "x"}]
                                         :returning [:id]})
                            db/jdbc-opts))
          issue-id (:id (jdbc/execute-one! (db/get-conn *ds*)
                          (sql/format {:insert-into :issues
                                       :values [{:title "Not yours" :user_id other-user}]
                                       :returning [:id]})
                          db/jdbc-opts))
          {:keys [status]} (convert! issue-id)]
      (is (= 404 status))
      (is (= 1 (row-count :issues [:= :id issue-id])) "the other user's issue is untouched"))))

(deftest records-the-delete-then-the-create
  (testing "the audit log holds both events, in the order the story happened"
    (let [issue-id (:id (create-issue! "Audited"))
          task-id (:id (:body (convert! issue-id)))
          events (db.event/list-events-for-user *ds* *user-id*)
          relevant (filter #(or (and (= "issue" (:entity_type %)) (= issue-id (:entity_id %)))
                                (and (= "task" (:entity_type %)) (= task-id (:entity_id %))))
                           events)
          ;; list-events-for-user is newest first, so the story reads bottom-up.
          story (reverse (map (juxt :entity_type :action) relevant))]
      (is (= [["issue" "create"] ["issue" "delete"] ["task" "create"]] (vec story)))
      (let [delete-event (first (filter #(= "delete" (:action %)) relevant))]
        (is (= "Audited" (get-in delete-event [:payload :snapshot :title]))
            "the delete carries the issue's snapshot")))))
