(ns et.tr.meets-archive-integration-test
  (:require [clojure.test :refer :all]
            [et.tr.integration-helpers :refer :all]
            [et.tr.db :as db]
            [next.jdbc :as jdbc]
            [honey.sql :as sql])
  (:import [java.time LocalDate]))

(use-fixtures :each with-integration-db)

(defn- days-ago [n]
  (str (.minusDays (LocalDate/now) n)))

(defn- days-ahead [n]
  (str (.plusDays (LocalDate/now) n)))

(defn- insert-meet! [title start-date]
  (:id (jdbc/execute-one! (db/get-conn *ds*)
         (sql/format {:insert-into :meets
                      :values [{:title title :user_id *user-id* :start_date start-date
                                :modified_at start-date :sort_order 1.0 :scope "both" :archived 0}]
                      :returning [:id]})
         db/jdbc-opts)))

(defn- archived? [id]
  (= 1 (:archived (jdbc/execute-one! (db/get-conn *ds*)
                    (sql/format {:select [:archived] :from [:meets] :where [:= :id id]})
                    db/jdbc-opts))))

(deftest archiving-future-dated-meet-is-rejected
  (testing "a meet dated after today cannot be archived"
    (let [id (insert-meet! "future-meet" (days-ahead 40))
          {:keys [status]} (PUT-json (str "/api/meets/" id "/archive") {})]
      (is (= 400 status))
      (is (false? (archived? id))))))

(deftest archiving-today-or-past-meet-succeeds
  (testing "a meet dated today or earlier can be archived"
    (let [today-id (insert-meet! "today-meet" (str (LocalDate/now)))
          past-id (insert-meet! "past-meet" (days-ago 7))]
      (is (= 200 (:status (PUT-json (str "/api/meets/" today-id "/archive") {}))))
      (is (true? (archived? today-id)))
      (is (= 200 (:status (PUT-json (str "/api/meets/" past-id "/archive") {}))))
      (is (true? (archived? past-id))))))
