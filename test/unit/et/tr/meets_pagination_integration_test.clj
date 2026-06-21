(ns et.tr.meets-pagination-integration-test
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
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:insert-into :meets
                 :values [{:title title :user_id *user-id* :start_date start-date
                           :modified_at start-date :sort_order 1.0 :scope "both" :archived 0}]})))

(defn- titles [coll] (set (map :title coll)))

(defn- seed-past! []
  (insert-meet! "past-recent" (days-ago 7))
  (insert-meet! "past-old" (days-ago 35)))

(defn- seed-upcoming! []
  (insert-meet! "up-soon" (days-ahead 7))
  (insert-meet! "up-far" (days-ahead 35)))

(deftest paged-past-envelope-shape
  (testing "a paged past request returns the {:items :has_more} envelope"
    (seed-past!)
    (let [{:keys [status body]} (GET-json "/api/meets?paged=true&sort=past&weekOffset=0&weekLimit=4")]
      (is (= 200 status))
      (is (contains? body :items))
      (is (contains? body :has_more)))))

(deftest unpaged-stays-a-bare-vector
  (testing "without paged the endpoint returns a bare vector (machine contract unchanged)"
    (seed-upcoming!)
    (let [{:keys [body]} (GET-json "/api/meets")]
      (is (vector? body))
      (is (= #{"up-soon" "up-far"} (titles body))))))

(deftest past-first-window-is-most-recent-four-weeks
  (testing "past sort, weekOffset=0 returns only the most recent 4 weeks back"
    (seed-past!)
    (let [{:keys [body]} (GET-json "/api/meets?paged=true&sort=past&weekOffset=0&weekLimit=4")]
      (is (= #{"past-recent"} (titles (:items body))))
      (is (true? (:has_more body))))))

(deftest past-pages-backward-without-overlap-or-gap
  (testing "past windows page backward in time, disjoint and complete"
    (seed-past!)
    (let [w0 (:body (GET-json "/api/meets?paged=true&sort=past&weekOffset=0&weekLimit=4"))
          w1 (:body (GET-json "/api/meets?paged=true&sort=past&weekOffset=4&weekLimit=4"))]
      (is (= #{"past-old"} (titles (:items w1))))
      (testing "no overlap"
        (is (empty? (clojure.set/intersection (titles (:items w0)) (titles (:items w1))))))
      (testing "no gap"
        (is (= #{"past-recent" "past-old"}
               (clojure.set/union (titles (:items w0)) (titles (:items w1)))))))))

(deftest past-has-more-transitions
  (testing "past has_more is true while older weeks remain, false once past them"
    (seed-past!)
    (is (true? (:has_more (:body (GET-json "/api/meets?paged=true&sort=past&weekOffset=0&weekLimit=4")))))
    (is (false? (:has_more (:body (GET-json "/api/meets?paged=true&sort=past&weekOffset=4&weekLimit=4")))))
    (is (false? (:has_more (:body (GET-json "/api/meets?paged=true&sort=past&weekOffset=8&weekLimit=4")))))))

(deftest upcoming-first-window-is-nearest-four-weeks
  (testing "upcoming sort, weekOffset=0 returns only the nearest 4 weeks forward"
    (seed-upcoming!)
    (let [{:keys [body]} (GET-json "/api/meets?paged=true&weekOffset=0&weekLimit=4")]
      (is (= #{"up-soon"} (titles (:items body))))
      (is (true? (:has_more body))))))

(deftest upcoming-pages-forward-without-overlap-or-gap
  (testing "upcoming windows page forward in time, disjoint and complete"
    (seed-upcoming!)
    (let [w0 (:body (GET-json "/api/meets?paged=true&weekOffset=0&weekLimit=4"))
          w1 (:body (GET-json "/api/meets?paged=true&weekOffset=4&weekLimit=4"))]
      (is (= #{"up-far"} (titles (:items w1))))
      (testing "no overlap"
        (is (empty? (clojure.set/intersection (titles (:items w0)) (titles (:items w1))))))
      (testing "no gap"
        (is (= #{"up-soon" "up-far"}
               (clojure.set/union (titles (:items w0)) (titles (:items w1)))))))))

(deftest upcoming-has-more-transitions
  (testing "upcoming has_more is true while further-future weeks remain, false once past them"
    (seed-upcoming!)
    (is (true? (:has_more (:body (GET-json "/api/meets?paged=true&weekOffset=0&weekLimit=4")))))
    (is (false? (:has_more (:body (GET-json "/api/meets?paged=true&weekOffset=4&weekLimit=4")))))
    (is (false? (:has_more (:body (GET-json "/api/meets?paged=true&weekOffset=8&weekLimit=4")))))))

(deftest directions-are-disjoint
  (testing "past and upcoming windows never serve each other's meets"
    (seed-past!)
    (seed-upcoming!)
    (let [past (:body (GET-json "/api/meets?paged=true&sort=past&weekOffset=0&weekLimit=4"))
          upcoming (:body (GET-json "/api/meets?paged=true&weekOffset=0&weekLimit=4"))]
      (is (not (contains? (titles (:items past)) "up-soon")))
      (is (not (contains? (titles (:items upcoming)) "past-recent"))))))
