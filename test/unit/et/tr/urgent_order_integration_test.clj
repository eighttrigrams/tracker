(ns et.tr.urgent-order-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [et.tr.integration-helpers :refer [GET-json POST-json PUT-json
                                               with-integration-db]]))

(use-fixtures :each with-integration-db)

(defn- add-task! [title]
  (:body (POST-json "/api/tasks" {:title title})))

(defn- task [id]
  (:body (GET-json (str "/api/tasks/" id))))

(defn- urgent-task! [title urgency]
  (let [id (:id (add-task! title))]
    (PUT-json (str "/api/tasks/" id "/urgency") {:urgency urgency})
    id))

(defn- add-issue! [title]
  (:body (POST-json "/api/issues" {:title title})))

(defn- issue [id]
  (:body (GET-json (str "/api/issues/" id))))

(defn- urgent-issue! [title urgency]
  (let [id (:id (add-issue! title))]
    (PUT-json (str "/api/issues/" id "/urgency") {:urgency urgency})
    id))

(defn- urgency-titles [rows urgency]
  (->> rows
       (filter #(= urgency (:urgency %)))
       (sort-by :sort_order_urgent)
       (mapv :title)))

(defn- urgent-tasks [urgency]
  (urgency-titles (:body (GET-json "/api/tasks?sort=today")) urgency))

(defn- urgent-issues [urgency]
  (urgency-titles (:body (GET-json "/api/issues")) urgency))

(deftest becoming-urgent-materializes-a-position
  (testing "a task that is not urgent has no Urgent Matters position"
    (let [id (:id (add-task! "Ordinary"))]
      (is (nil? (:sort_order_urgent (task id))))))

  (testing "each newly urgent task goes to the top of its block"
    (let [first-id (urgent-task! "First urgent" "urgent")
          second-id (urgent-task! "Second urgent" "urgent")]
      (is (some? (:sort_order_urgent (task first-id))))
      (is (< (:sort_order_urgent (task second-id)) (:sort_order_urgent (task first-id))))
      (is (= ["Second urgent" "First urgent"] (urgent-tasks "urgent")))))

  (testing "leaving Urgent Matters gives the position up"
    (let [id (urgent-task! "Briefly urgent" "urgent")]
      (PUT-json (str "/api/tasks/" id "/urgency") {:urgency "default"})
      (is (nil? (:sort_order_urgent (task id))))))

  (testing "the two blocks are placed separately"
    (let [id (urgent-task! "Escalating" "urgent")
          in-urgent (:sort_order_urgent (task id))]
      (urgent-task! "Already super" "superurgent")
      (PUT-json (str "/api/tasks/" id "/urgency") {:urgency "superurgent"})
      (is (not= in-urgent (:sort_order_urgent (task id))))
      (is (= ["Escalating" "Already super"] (urgent-tasks "superurgent")))))

  (testing "setting the urgency it already has leaves the position alone"
    (let [id (urgent-task! "Steady" "urgent")
          placed (:sort_order_urgent (task id))]
      (urgent-task! "Newer" "urgent")
      (PUT-json (str "/api/tasks/" id "/urgency") {:urgency "urgent"})
      (is (= placed (:sort_order_urgent (task id)))))))

(deftest an-issue-becoming-urgent-is-placed-the-same-way
  (let [first-id (urgent-issue! "First issue" "urgent")
        second-id (urgent-issue! "Second issue" "urgent")]
    (is (< (:sort_order_urgent (issue second-id)) (:sort_order_urgent (issue first-id))))
    (is (= ["Second issue" "First issue"] (urgent-issues "urgent")))
    (PUT-json (str "/api/issues/" first-id "/urgency") {:urgency "default"})
    (is (nil? (:sort_order_urgent (issue first-id))))))

(deftest reordering-in-urgent-matters-leaves-the-tasks-page-alone
  (let [bottom (urgent-task! "Bottom" "urgent")
        top (urgent-task! "Top" "urgent")
        before (mapv #(:sort_order (task %)) [bottom top])]
    (is (= ["Top" "Bottom"] (urgent-tasks "urgent")))
    (let [{:keys [status body]} (POST-json (str "/api/tasks/" top "/reorder-urgent")
                                           {:target-task-id bottom :position "after"})]
      (is (= 200 status))
      (is (true? (:success body)))
      (is (= (:sort_order_urgent body) (:sort_order_urgent (task top)))))
    (is (= ["Bottom" "Top"] (urgent-tasks "urgent")))
    (is (= before (mapv #(:sort_order (task %)) [bottom top])))))

(deftest reordering-on-the-tasks-page-leaves-urgent-matters-alone
  (let [bottom (urgent-task! "Bottom" "urgent")
        top (urgent-task! "Top" "urgent")
        before (mapv #(:sort_order_urgent (task %)) [bottom top])]
    (is (= 200 (:status (POST-json (str "/api/tasks/" top "/reorder")
                                   {:target-task-id bottom :position "after"}))))
    (is (= before (mapv #(:sort_order_urgent (task %)) [bottom top])))
    (is (= ["Top" "Bottom"] (urgent-tasks "urgent")))))

(deftest reordering-an-issue-in-urgent-matters-leaves-the-issues-page-alone
  (let [bottom (urgent-issue! "Bottom issue" "urgent")
        top (urgent-issue! "Top issue" "urgent")
        before (mapv #(:sort_order (issue %)) [bottom top])]
    (is (= ["Top issue" "Bottom issue"] (urgent-issues "urgent")))
    (is (= 200 (:status (POST-json (str "/api/issues/" top "/reorder-urgent")
                                   {:target-issue-id bottom :position "after"}))))
    (is (= ["Bottom issue" "Top issue"] (urgent-issues "urgent")))
    (is (= before (mapv #(:sort_order (issue %)) [bottom top])))))

(deftest reordering-on-the-issues-page-leaves-urgent-matters-alone
  (let [bottom (urgent-issue! "Bottom issue" "urgent")
        top (urgent-issue! "Top issue" "urgent")
        before (mapv #(:sort_order_urgent (issue %)) [bottom top])]
    (is (= 200 (:status (POST-json (str "/api/issues/" top "/reorder")
                                   {:target-issue-id bottom :position "after"}))))
    (is (= before (mapv #(:sort_order_urgent (issue %)) [bottom top])))
    (is (= ["Top issue" "Bottom issue"] (urgent-issues "urgent")))))

(deftest a-target-outside-urgent-matters-is-refused
  (testing "tasks"
    (let [dragged (urgent-task! "Dragged" "urgent")
          plain (:id (add-task! "Not urgent"))
          placed (:sort_order_urgent (task dragged))]
      (is (= 404 (:status (POST-json (str "/api/tasks/" dragged "/reorder-urgent")
                                     {:target-task-id plain :position "after"}))))
      (is (= 404 (:status (POST-json (str "/api/tasks/" dragged "/reorder-urgent")
                                     {:target-task-id 99999 :position "after"}))))
      (is (= placed (:sort_order_urgent (task dragged))))))

  (testing "issues"
    (let [dragged (urgent-issue! "Dragged issue" "urgent")
          plain (:id (add-issue! "Not urgent issue"))
          placed (:sort_order_urgent (issue dragged))]
      (is (= 404 (:status (POST-json (str "/api/issues/" dragged "/reorder-urgent")
                                     {:target-issue-id plain :position "after"}))))
      (is (= placed (:sort_order_urgent (issue dragged)))))))

(deftest the-two-urgency-blocks-are-reordered-independently
  (let [super-a (urgent-task! "Super A" "superurgent")
        super-b (urgent-task! "Super B" "superurgent")
        urgent-a (urgent-task! "Urgent A" "urgent")
        urgent-b (urgent-task! "Urgent B" "urgent")
        before (:sort_order_urgent (task urgent-a))]
    (is (= 200 (:status (POST-json (str "/api/tasks/" super-b "/reorder-urgent")
                                   {:target-task-id super-a :position "after"}))))
    (is (= ["Super A" "Super B"] (urgent-tasks "superurgent")))
    (is (= ["Urgent B" "Urgent A"] (urgent-tasks "urgent")))
    (is (= before (:sort_order_urgent (task urgent-a))))
    (is (some? urgent-b))))
