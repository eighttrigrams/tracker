(ns et.tr.issue-urgency-integration-test
  (:require [clojure.test :refer :all]
            [et.tr.integration-helpers :refer :all]))

(use-fixtures :each with-integration-db)

(defn- create-issue! [title]
  (:id (:body (POST-json "/api/issues" {:title title}))))

(deftest set-issue-urgency-round-trips
  (testing "PUT /api/issues/:id/urgency sets urgent and superurgent"
    (let [id (create-issue! "Leaky roof")]
      (let [{:keys [status body]} (PUT-json (str "/api/issues/" id "/urgency") {:urgency "urgent"})]
        (is (= 200 status))
        (is (= "urgent" (:urgency body))))
      (let [{:keys [status body]} (PUT-json (str "/api/issues/" id "/urgency") {:urgency "superurgent"})]
        (is (= 200 status))
        (is (= "superurgent" (:urgency body)))))))

(deftest set-issue-urgency-rejects-invalid
  (testing "an invalid urgency value yields 400 and does not change the row"
    (let [id (create-issue! "Steady issue")
          {:keys [status]} (PUT-json (str "/api/issues/" id "/urgency") {:urgency "bogus"})]
      (is (= 400 status))
      (is (= "default" (:urgency (:body (GET-json (str "/api/issues/" id)))))))))

(deftest set-issue-urgency-unknown-issue-404
  (testing "unknown issue yields 404"
    (let [{:keys [status]} (PUT-json "/api/issues/99999/urgency" {:urgency "urgent"})]
      (is (= 404 status)))))
