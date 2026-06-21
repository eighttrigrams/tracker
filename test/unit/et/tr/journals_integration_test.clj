(ns et.tr.journals-integration-test
  (:require [clojure.test :refer :all]
            [et.tr.integration-helpers :refer :all]
            [et.tr.db.journal :as db.journal]))

(use-fixtures :each with-integration-db)

(deftest taken-dates-route-returns-entry-dates
  (let [journal (db.journal/add-journal *ds* *user-id* "Route Journal")]
    (testing "empty when no entries exist"
      (let [{:keys [status body]} (GET-json (str "/api/journals/" (:id journal) "/taken-dates"))]
        (is (= 200 status))
        (is (= [] (:dates body)))))

    (testing "returns the dates of created entries"
      (POST-json (str "/api/journals/" (:id journal) "/create-entry") {:date "2026-05-01"})
      (POST-json (str "/api/journals/" (:id journal) "/create-entry") {:date "2026-05-08"})
      (let [{:keys [status body]} (GET-json (str "/api/journals/" (:id journal) "/taken-dates"))]
        (is (= 200 status))
        (is (= #{"2026-05-01" "2026-05-08"} (set (:dates body))))))

    (testing "404 for a journal that does not exist"
      (let [{:keys [status]} (GET-json "/api/journals/99999/taken-dates")]
        (is (= 404 status))))))
