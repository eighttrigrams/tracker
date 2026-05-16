(ns et.tr.source-worker-podcast-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [et.tr.db :as db]
            [et.tr.db.message :as db.message]
            [et.tr.db.podcast :as db.podcast]
            [et.tr.source-worker :as source-worker]
            [et.tr.podcast :as podcast]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]))

(use-fixtures :each with-in-memory-db)

(defn- enable-mail! [user-id]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:update :users
                 :set {:has_mail 1}
                 :where [:= :id user-id]})))

(defn- list-inbox []
  (db.message/list-messages *ds* *user-id* {:view :inbox :sort-mode :recent}))

(defn- with-mocked-fetch [episodes f]
  (with-redefs [podcast/get-latest-episodes (fn [_feed-url] episodes)]
    (f)))

(deftest first-poll-marks-existing-episodes-as-notified
  (enable-mail! *user-id*)
  (db.podcast/upsert-settings *ds* *user-id* {:enabled 1 :polling-minutes 60})
  (db.podcast/add-feed *ds* *user-id*
    {:feed-url "https://example.com/feed.xml" :name "Foo" :enabled 1})
  (with-mocked-fetch [{:guid "g1" :title "Old1" :published "2000-01-01T00:00:00Z"
                       :link "https://e.com/1" :author "A"}
                      {:guid "g2" :title "Old2" :published "2000-01-02T00:00:00Z"
                       :link "https://e.com/2" :author "A"}]
    #(source-worker/run-podcast-tick *ds*))
  (is (empty? (list-inbox)))
  (is (db.podcast/episode-notified? *ds* *user-id* "g1"))
  (is (db.podcast/episode-notified? *ds* *user-id* "g2")))

(deftest later-episodes-are-forwarded
  (enable-mail! *user-id*)
  (db.podcast/upsert-settings *ds* *user-id* {:enabled 1 :polling-minutes 60})
  (db.podcast/add-feed *ds* *user-id*
    {:feed-url "https://example.com/bar.xml" :name "Bar Show" :enabled 1})
  (with-mocked-fetch [{:guid "gNew" :title "Brand new"
                       :published "2999-01-01T00:00:00Z"
                       :link "https://e.com/n" :author "Bar"}]
    #(source-worker/run-podcast-tick *ds*))
  (let [messages (list-inbox)]
    (is (= 1 (count messages)))
    (let [m (first messages)]
      (is (= "Podcasts" (:sender m)))
      (is (str/includes? (:title m) "Bar Show")))
    (is (db.podcast/episode-notified? *ds* *user-id* "gNew"))))

(deftest disabled-source-skips-user
  (enable-mail! *user-id*)
  (db.podcast/upsert-settings *ds* *user-id* {:enabled 0 :polling-minutes 60})
  (db.podcast/add-feed *ds* *user-id*
    {:feed-url "https://example.com/x.xml" :name "X" :enabled 1})
  (with-redefs [podcast/get-latest-episodes
                (fn [_] (throw (ex-info "should not be called" {})))]
    (source-worker/run-podcast-tick *ds*))
  (is (empty? (list-inbox))))

(deftest deduplicates-across-ticks
  (enable-mail! *user-id*)
  (db.podcast/upsert-settings *ds* *user-id* {:enabled 1 :polling-minutes 0})
  (db.podcast/add-feed *ds* *user-id*
    {:feed-url "https://example.com/dup.xml" :name "Dup" :enabled 1})
  (with-mocked-fetch [{:guid "gOnce" :title "Once"
                       :published "2999-02-02T00:00:00Z"
                       :link "https://e.com/o" :author "Dup"}]
    (fn []
      (source-worker/run-podcast-tick *ds*)
      (source-worker/run-podcast-tick *ds*)))
  (is (= 1 (count (list-inbox)))))

(deftest polling-cycle-respected
  (enable-mail! *user-id*)
  (db.podcast/upsert-settings *ds* *user-id* {:enabled 1 :polling-minutes 60})
  (db.podcast/add-feed *ds* *user-id*
    {:feed-url "https://example.com/c.xml" :name "Cad" :enabled 1})
  (db.podcast/set-last-polled! *ds* *user-id*)
  (let [calls (atom 0)]
    (with-redefs [podcast/get-latest-episodes (fn [_] (swap! calls inc) [])]
      (source-worker/run-podcast-tick *ds*)
      (is (zero? @calls) "user with fresh last_polled_at should be skipped"))))
