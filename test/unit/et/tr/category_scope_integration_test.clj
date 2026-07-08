(ns et.tr.category-scope-integration-test
  (:require [clojure.test :refer :all]
            [et.tr.integration-helpers :refer :all]))

(use-fixtures :each with-integration-db)

(defn- create! [path name]
  (:body (POST-json path {:name name})))

(deftest people-scope-round-trips
  (testing "PUT /api/people/:id/scope sets private and work"
    (let [id (:id (create! "/api/people" "Alice"))]
      (let [{:keys [status body]} (PUT-json (str "/api/people/" id "/scope") {:scope "work"})]
        (is (= 200 status))
        (is (= "work" (:scope body))))
      (let [{:keys [status body]} (PUT-json (str "/api/people/" id "/scope") {:scope "private"})]
        (is (= 200 status))
        (is (= "private" (:scope body)))))))

(deftest people-scope-rejects-invalid
  (testing "an invalid scope yields 400 and leaves the row at both"
    (let [id (:id (create! "/api/people" "Bob"))
          {:keys [status]} (PUT-json (str "/api/people/" id "/scope") {:scope "bogus"})]
      (is (= 400 status))
      (is (= "both" (:scope (:body (GET-json (str "/api/people/" id)))))))))

(deftest people-scope-unknown-404
  (testing "unknown person yields 404"
    (let [{:keys [status]} (PUT-json "/api/people/99999/scope" {:scope "work"})]
      (is (= 404 status)))))

(deftest available-people-filtered-by-scope-on-backend
  (testing "GET /api/people?context= returns only categories applicable to
            the requested scope (default 'both' shows in every scope) — the
            availability filtering happens server-side"
    (let [alice (:id (create! "/api/people" "Alice"))
          bob   (:id (create! "/api/people" "Bob"))
          _carol (:id (create! "/api/people" "Carol"))]
      (PUT-json (str "/api/people/" alice "/scope") {:scope "private"})
      (PUT-json (str "/api/people/" bob "/scope") {:scope "work"})
      ;; Carol stays 'both'.
      (testing "work context: work + both, private hidden"
        (let [{:keys [status body]} (GET-json "/api/people?context=work")]
          (is (= 200 status))
          (is (= #{"Bob" "Carol"} (set (map :name body))))))
      (testing "private context: private + both, work hidden"
        (let [{:keys [body]} (GET-json "/api/people?context=private")]
          (is (= #{"Alice" "Carol"} (set (map :name body))))))
      (testing "strict work context: only work"
        (let [{:keys [body]} (GET-json "/api/people?context=work&strict=true")]
          (is (= #{"Bob"} (set (map :name body))))))
      (testing "no context: all categories"
        (let [{:keys [body]} (GET-json "/api/people")]
          (is (= #{"Alice" "Bob" "Carol"} (set (map :name body)))))))))

(deftest available-projects-filtered-by-scope-on-backend
  (testing "the backend scope-availability filter also applies to projects"
    (let [alpha (:id (create! "/api/projects" "Alpha"))
          beta  (:id (create! "/api/projects" "Beta"))]
      (PUT-json (str "/api/projects/" alpha "/scope") {:scope "private"})
      (PUT-json (str "/api/projects/" beta "/scope") {:scope "work"})
      (let [{:keys [status body]} (PUT-json (str "/api/projects/" alpha "/scope") {:scope "private"})]
        (is (= 200 status))
        (is (= "private" (:scope body))))
      (testing "work context hides the private project"
        (let [{:keys [body]} (GET-json "/api/projects?context=work")]
          (is (= #{"Beta"} (set (map :name body))))))
      (testing "private context hides the work project"
        (let [{:keys [body]} (GET-json "/api/projects?context=private")]
          (is (= #{"Alpha"} (set (map :name body)))))))))
