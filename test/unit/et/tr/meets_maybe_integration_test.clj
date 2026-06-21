(ns et.tr.meets-maybe-integration-test
  (:require [clojure.test :refer :all]
            [et.tr.integration-helpers :refer :all]
            [et.tr.db :as db]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]))

(use-fixtures :each with-integration-db)

(defn- insert-meet! [title]
  (:id (jdbc/execute-one! (db/get-conn *ds*)
         (sql/format {:insert-into :meets
                      :values [{:title title :user_id *user-id* :start_date "2099-01-01"
                                :modified_at "2099-01-01" :sort_order 1.0 :scope "both" :archived 0}]
                      :returning [:id]})
         db/jdbc-opts)))

(deftest set-meet-maybe-round-trips
  (testing "PUT /api/meets/:id/maybe flips the flag both ways"
    (let [id (insert-meet! "Standup")]
      (let [{:keys [status body]} (PUT-json (str "/api/meets/" id "/maybe") {:maybe true})]
        (is (= 200 status))
        (is (= 1 (:maybe body))))
      (let [{:keys [status body]} (PUT-json (str "/api/meets/" id "/maybe") {:maybe false})]
        (is (= 200 status))
        (is (= 0 (:maybe body)))))))

(deftest set-meet-maybe-requires-field
  (testing "missing :maybe yields 400"
    (let [id (insert-meet! "Standup")
          {:keys [status]} (PUT-json (str "/api/meets/" id "/maybe") {})]
      (is (= 400 status)))))

(deftest set-meet-maybe-unknown-meet-404
  (testing "unknown meet yields 404"
    (let [{:keys [status]} (PUT-json "/api/meets/99999/maybe" {:maybe true})]
      (is (= 404 status)))))
