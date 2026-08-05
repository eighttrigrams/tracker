(ns et.tr.category-group-integration-test
  "PUT /api/categories/:id/group — moving a category into another Group, through
  the real routing + handler + DB stack.

  The db-level guarantees live in et.tr.categories-db-test; what is asserted
  here is the HTTP contract around them: the status codes, that the six group
  URL spaces all exist and stay disjoint, and that a moved item is served under
  its new group by every endpoint that reports categories."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [et.tr.integration-helpers :refer [GET-json POST-json PUT-json with-integration-db]]))

(use-fixtures :each with-integration-db)

(defn- create! [segment name]
  (:body (POST-json (str "/api/" segment "/") {:name name})))

(defn- move! [id group]
  (PUT-json (str "/api/categories/" id "/group") {:group group}))

(deftest every-group-has-its-own-url-space
  (testing "all six Groups can be created through and listed from their own path"
    (doseq [[segment name] [["people" "A Person"] ["places" "A Place"]
                            ["workstreams" "A Workstream"] ["projects" "A Project"]
                            ["goals" "A Goal"] ["assets" "An Asset"]]]
      (let [{:keys [status body]} (POST-json (str "/api/" segment "/") {:name name})]
        (is (= 201 status) (str segment " create failed"))
        (is (= name (:name body))))
      (let [{:keys [status body]} (GET-json (str "/api/" segment "/"))]
        (is (= 200 status))
        (is (= [name] (map :name body)) (str segment " does not list its own item")))))
  (testing "and the six lists stay disjoint"
    (doseq [segment ["people" "places" "workstreams" "projects" "goals" "assets"]]
      (is (= 1 (count (:body (GET-json (str "/api/" segment "/")))))))))

(deftest moving-a-project-to-a-workstream-and-back
  (let [project (create! "projects" "Renovations")
        id (:id project)]
    (testing "the move answers 200 with the row in its new group"
      (let [{:keys [status body]} (move! id "workstream")]
        (is (= 200 status))
        (is (= id (:id body)) "the id must not change — that is the point")
        (is (= "workstream" (:category_type body)))
        (is (= "Renovations" (:name body)))))
    (testing "it is served by /api/workstreams and gone from /api/projects"
      (is (= ["Renovations"] (map :name (:body (GET-json "/api/workstreams/")))))
      (is (= [] (:body (GET-json "/api/projects/")))))
    (testing "GET /api/projects/:id now 404s and GET /api/workstreams/:id serves it"
      (is (= 404 (:status (GET-json (str "/api/projects/" id)))))
      (is (= 200 (:status (GET-json (str "/api/workstreams/" id))))))
    (testing "moving it back restores it, same id"
      (let [{:keys [status body]} (move! id "project")]
        (is (= 200 status))
        (is (= id (:id body)))
        (is (= "project" (:category_type body))))
      (is (= ["Renovations"] (map :name (:body (GET-json "/api/projects/")))))
      (is (= [] (:body (GET-json "/api/workstreams/")))))))

(deftest a-move-keeps-associations-in-two-entity-types-at-once
  (let [project (create! "projects" "Renovations")
        id (:id project)
        task (:body (POST-json "/api/tasks/" {:title "Fix the roof"}))
        issue (:body (POST-json "/api/issues/" {:title "Roof leaks"}))
        ;; An uncategorized second row of each, so a filter assertion below
        ;; cannot pass by returning everything: with one row the count is 1
        ;; whether the filter works or is ignored entirely.
        _ (POST-json "/api/tasks/" {:title "Unrelated task"})
        _ (POST-json "/api/issues/" {:title "Unrelated issue"})]
    (POST-json (str "/api/tasks/" (:id task) "/categorize") {:category-type "project" :category-id id})
    (POST-json (str "/api/issues/" (:id issue) "/categorize") {:category-type "project" :category-id id})
    (testing "precondition: both carry it as a project, and the filter discriminates"
      (is (= 2 (count (:body (GET-json "/api/tasks/")))))
      (is (= 1 (count (:body (GET-json "/api/tasks?projects=Renovations")))))
      (is (= 1 (count (:body (GET-json "/api/issues?projects=Renovations"))))))

    (is (= 200 (:status (move! id "workstream"))))

    (testing "both now carry it as a workstream, and neither lost it"
      (let [t (first (filter #(= "Fix the roof" (:title %)) (:body (GET-json "/api/tasks/"))))
            i (first (filter #(= "Roof leaks" (:title %)) (:body (GET-json "/api/issues/"))))]
        (is (= ["Renovations"] (map :name (:workstreams t))))
        (is (= [] (:projects t)))
        (is (= ["Renovations"] (map :name (:workstreams i))))
        (is (= [] (:projects i)))))

    (testing "and filtering by the new group finds them"
      (is (= 1 (count (:body (GET-json "/api/tasks?workstreams=Renovations")))))
      (is (= 1 (count (:body (GET-json "/api/issues?workstreams=Renovations"))))))
    (testing "while filtering by the old one finds nothing"
      (is (= 0 (count (:body (GET-json "/api/tasks?projects=Renovations"))))))))

(deftest move-rejects-an-unknown-group
  (let [project (create! "projects" "Renovations")]
    (let [{:keys [status body]} (move! (:id project) "sideways")]
      (is (= 400 status))
      (is (false? (:success body)))
      (is (re-find #"Invalid group" (:error body))))
    (testing "and the item did not move"
      (is (= ["Renovations"] (map :name (:body (GET-json "/api/projects/"))))))))

(deftest move-404s-on-a-category-that-is-not-there
  (let [{:keys [status body]} (move! 999999 "workstream")]
    (is (= 404 status))
    (is (false? (:success body)))))

(deftest the-move-endpoint-is-in-describe
  (testing "an endpoint the API does not describe is an endpoint a machine user
            cannot find"
    (let [endpoints (:endpoints (:body (GET-json "/api/describe")))
          entry (first (filter #(= "set-category-group-handler" (:name %)) endpoints))]
      (is (some? entry) "set-category-group-handler missing from /api/describe")
      (is (= "et.tr.server.category-handler" (:ns entry)))
      (is (re-find #"PUT /api/categories/:id/group" (:doc entry)))
      (is (re-find #"workstream" (:doc entry)) "the doc must name the valid groups"))
    (testing "and so is every handler the two new groups added"
      (let [names (set (map :name (:endpoints (:body (GET-json "/api/describe")))))]
        (doseq [n ["list-workstreams-handler" "add-workstream-handler"
                   "update-workstream-handler" "get-workstream-handler"
                   "set-workstream-scope-handler"
                   "list-assets-handler" "add-asset-handler"
                   "update-asset-handler" "get-asset-handler"
                   "set-asset-scope-handler"]]
          (is (contains? names n) (str n " missing from describe")))))))
