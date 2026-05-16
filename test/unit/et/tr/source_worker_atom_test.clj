(ns et.tr.source-worker-atom-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [et.tr.db :as db]
            [et.tr.db.message :as db.message]
            [et.tr.db.atom-feed :as db.atom]
            [et.tr.source-worker :as source-worker]
            [et.tr.atom-feed :as atom-feed]
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

(defn- with-mocked-fetch [entries f]
  (with-redefs [atom-feed/get-latest-entries (fn [_feed-url] entries)]
    (f)))

(deftest first-poll-marks-existing-entries-as-notified
  (enable-mail! *user-id*)
  (db.atom/upsert-settings *ds* *user-id* {:enabled 1 :polling-minutes 60})
  (db.atom/add-feed *ds* *user-id*
    {:feed-url "https://blog.example.com/feed.xml" :name "Foo Blog" :enabled 1})
  (with-mocked-fetch [{:entry-id "e1" :title "Old1" :published "2000-01-01T00:00:00Z"
                       :link "https://e.com/1" :author "A" :summary "S" :content "C"}
                      {:entry-id "e2" :title "Old2" :published "2000-01-02T00:00:00Z"
                       :link "https://e.com/2" :author "A" :summary "S" :content "C"}]
    #(source-worker/run-atom-tick *ds*))
  (is (empty? (list-inbox)))
  (is (db.atom/entry-notified? *ds* *user-id* "e1"))
  (is (db.atom/entry-notified? *ds* *user-id* "e2")))

(deftest later-entries-are-forwarded
  (enable-mail! *user-id*)
  (db.atom/upsert-settings *ds* *user-id* {:enabled 1 :polling-minutes 60})
  (db.atom/add-feed *ds* *user-id*
    {:feed-url "https://blog.example.com/bar.xml" :name "Bar Blog" :enabled 1})
  (with-mocked-fetch [{:entry-id "eNew" :title "Brand new"
                       :published "2999-01-01T00:00:00Z"
                       :link "https://e.com/n" :author "Bar"
                       :summary "Hello" :content "World"}]
    #(source-worker/run-atom-tick *ds*))
  (let [messages (list-inbox)]
    (is (= 1 (count messages)))
    (let [m (first messages)]
      (is (= "Bar Blog" (:sender m)) "atom messages use feed name as sender")
      (is (str/includes? (:title m) "Bar Blog")))
    (is (db.atom/entry-notified? *ds* *user-id* "eNew"))))

(deftest disabled-source-skips-user
  (enable-mail! *user-id*)
  (db.atom/upsert-settings *ds* *user-id* {:enabled 0 :polling-minutes 60})
  (db.atom/add-feed *ds* *user-id*
    {:feed-url "https://blog.example.com/x.xml" :name "X" :enabled 1})
  (with-redefs [atom-feed/get-latest-entries
                (fn [_] (throw (ex-info "should not be called" {})))]
    (source-worker/run-atom-tick *ds*))
  (is (empty? (list-inbox))))

(deftest deduplicates-across-ticks
  (enable-mail! *user-id*)
  (db.atom/upsert-settings *ds* *user-id* {:enabled 1 :polling-minutes 0})
  (db.atom/add-feed *ds* *user-id*
    {:feed-url "https://blog.example.com/dup.xml" :name "Dup" :enabled 1})
  (with-mocked-fetch [{:entry-id "eOnce" :title "Once"
                       :published "2999-02-02T00:00:00Z"
                       :link "https://e.com/o" :author "Dup"}]
    (fn []
      (source-worker/run-atom-tick *ds*)
      (source-worker/run-atom-tick *ds*)))
  (is (= 1 (count (list-inbox)))))

(deftest polling-cycle-respected
  (enable-mail! *user-id*)
  (db.atom/upsert-settings *ds* *user-id* {:enabled 1 :polling-minutes 60})
  (db.atom/add-feed *ds* *user-id*
    {:feed-url "https://blog.example.com/c.xml" :name "Cad" :enabled 1})
  (db.atom/set-last-polled! *ds* *user-id*)
  (let [calls (atom 0)]
    (with-redefs [atom-feed/get-latest-entries (fn [_] (swap! calls inc) [])]
      (source-worker/run-atom-tick *ds*)
      (is (zero? @calls)))))
