(ns et.tr.journals-integration-test
  (:require [clojure.test :refer :all]
            [et.tr.integration-helpers :refer :all]
            [et.tr.db.journal :as db.journal]
            [et.tr.db.user :as db.user]))

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

(deftest taken-dates-route-is-scoped-to-owner
  (let [owner-journal (db.journal/add-journal *ds* *user-id* "Owner Journal")
        other-user (db.user/create-user *ds* "other-user" "testpass")
        other-journal (db.journal/add-journal *ds* (:id other-user) "Other's Journal")]
    (POST-json (str "/api/journals/" (:id owner-journal) "/create-entry") {:date "2026-05-01"})
    (testing "the owner gets the dates of their own journal"
      (let [{:keys [status body]} (GET-json-as *user-id* (str "/api/journals/" (:id owner-journal) "/taken-dates"))]
        (is (= 200 status))
        (is (= #{"2026-05-01"} (set (:dates body))))))
    (testing "another user requesting the owner's journal gets 404, not its dates"
      (let [{:keys [status]} (GET-json-as (:id other-user) (str "/api/journals/" (:id owner-journal) "/taken-dates"))]
        (is (= 404 status))))
    (testing "the owner cannot read another user's journal dates"
      (let [{:keys [status]} (GET-json-as *user-id* (str "/api/journals/" (:id other-journal) "/taken-dates"))]
        (is (= 404 status))))))
