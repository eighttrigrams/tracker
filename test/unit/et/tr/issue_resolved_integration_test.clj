(ns et.tr.issue-resolved-integration-test
  "HTTP-layer tests for the issue resolved lifecycle: PUT /api/issues/:id/resolved
  (happy path, the undone-task rejection, reopening), the resolved sort
  partitioning, and the guard that blocks task creation on a resolved issue."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [et.tr.integration-helpers :refer [with-integration-db
                                               POST-json PUT-json GET-json]]))

(use-fixtures :each with-integration-db)

(defn- create-issue! [title]
  (:id (:body (POST-json "/api/issues" {:title title}))))

(defn- create-task-for-issue! [issue-id title]
  (:id (:body (POST-json (str "/api/issues/" issue-id "/create-task") {:title title}))))

(deftest set-issue-resolved-round-trips
  (testing "PUT /api/issues/:id/resolved resolves and reopens"
    (let [id (create-issue! "Leaky roof")]
      (let [{:keys [status body]} (PUT-json (str "/api/issues/" id "/resolved") {:resolved true})]
        (is (= 200 status))
        (is (= 1 (:resolved body)))
        (is (some? (:resolved_at body))))
      (let [{:keys [status body]} (PUT-json (str "/api/issues/" id "/resolved") {:resolved false})]
        (is (= 200 status))
        (is (= 0 (:resolved body)))
        (is (nil? (:resolved_at body)))))))

(deftest set-issue-resolved-requires-body-field
  (testing "a missing :resolved field yields 400"
    (let [id (create-issue! "No field")
          {:keys [status]} (PUT-json (str "/api/issues/" id "/resolved") {})]
      (is (= 400 status)))))

(deftest set-issue-resolved-unknown-issue-404
  (testing "unknown issue yields 404"
    (let [{:keys [status]} (PUT-json "/api/issues/99999/resolved" {:resolved true})]
      (is (= 404 status)))))

(deftest set-issue-resolved-rejected-while-task-undone
  (testing "resolving is blocked with 409 while a belonging task is still undone"
    (let [id (create-issue! "Has open work")
          task-id (create-task-for-issue! id "Open task")
          {:keys [status body]} (PUT-json (str "/api/issues/" id "/resolved") {:resolved true})]
      (is (= 409 status))
      (is (some? (:error body)))
      (is (= 0 (:resolved (:body (GET-json (str "/api/issues/" id))))) "issue stays unresolved")
      (testing "once the task is done, the issue can be resolved"
        (PUT-json (str "/api/tasks/" task-id "/done") {:done true})
        (is (= 200 (:status (PUT-json (str "/api/issues/" id "/resolved") {:resolved true}))))))))

(deftest resolved-sort-partitions-the-list
  (testing "the resolved sort mode shows only resolved issues; others exclude them"
    (let [open-id (create-issue! "Open matter")
          done-id (create-issue! "Finished matter")]
      (PUT-json (str "/api/issues/" done-id "/resolved") {:resolved true})
      (let [titles (set (map :title (:body (GET-json "/api/issues?sortMode=recent"))))]
        (is (contains? titles "Open matter"))
        (is (not (contains? titles "Finished matter"))))
      (let [titles (set (map :title (:body (GET-json "/api/issues?sortMode=resolved"))))]
        (is (contains? titles "Finished matter"))
        (is (not (contains? titles "Open matter"))))
      (is (some? open-id)))))

(deftest create-task-blocked-on-resolved-issue
  (testing "POST /api/issues/:id/create-task is rejected with 409 once the issue is resolved"
    (let [id (create-issue! "Wrapped up")]
      (PUT-json (str "/api/issues/" id "/resolved") {:resolved true})
      (let [{:keys [status body]} (POST-json (str "/api/issues/" id "/create-task") {:title "New work"})]
        (is (= 409 status))
        (is (some? (:error body))))
      (testing "reopening the issue re-enables task creation"
        (PUT-json (str "/api/issues/" id "/resolved") {:resolved false})
        (is (= 201 (:status (POST-json (str "/api/issues/" id "/create-task") {:title "New work"}))))))))
