(ns et.tr.source-worker-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [et.tr.db :as db]
            [et.tr.db.message :as db.message]
            [et.tr.db.youtube :as db.youtube]
            [et.tr.source-worker :as source-worker]
            [et.tr.youtube :as youtube]
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

(defn- with-mocked-fetch [videos f]
  (with-redefs [youtube/get-latest-videos (fn [_channel-id] videos)
                youtube/get-video-duration-minutes (fn [_id] nil)]
    (f)))

(deftest first-poll-marks-existing-videos-as-notified
  (testing "videos published before the channel was registered are skipped"
    (enable-mail! *user-id*)
    (db.youtube/upsert-settings *ds* *user-id* {:enabled 1 :polling-minutes 60})
    (db.youtube/add-channel *ds* *user-id*
      {:channel-id "UCfoo" :name nil :min-duration-minutes nil :enabled 1})
    (let [pre-existing [{:video-id "v1" :title "Old1" :published "2000-01-01T00:00:00Z"
                         :link "https://youtu.be/v1" :author "Foo"}
                        {:video-id "v2" :title "Old2" :published "2000-01-02T00:00:00Z"
                         :link "https://youtu.be/v2" :author "Foo"}]]
      (with-mocked-fetch pre-existing
        #(source-worker/run-youtube-tick *ds*)))
    (is (empty? (list-inbox))
        "first poll should not produce inbox entries for pre-existing videos")
    (is (db.youtube/video-notified? *ds* *user-id* "v1"))
    (is (db.youtube/video-notified? *ds* *user-id* "v2"))))

(deftest later-videos-are-forwarded
  (testing "videos published after channel registration land in the inbox"
    (enable-mail! *user-id*)
    (db.youtube/upsert-settings *ds* *user-id* {:enabled 1 :polling-minutes 60})
    (db.youtube/add-channel *ds* *user-id*
      {:channel-id "UCbar" :name "Bar Override" :min-duration-minutes nil :enabled 1})
    (with-mocked-fetch [{:video-id "vNew" :title "Brand new"
                         :published "2999-01-01T00:00:00Z"
                         :link "https://youtu.be/vNew" :author "Bar"}]
      #(source-worker/run-youtube-tick *ds*))
    (let [messages (list-inbox)]
      (is (= 1 (count messages)))
      (let [m (first messages)]
        (is (= "YouTube" (:sender m)))
        (is (str/includes? (:title m) "Bar Override")
            "name override should appear in the message title"))
      (is (db.youtube/video-notified? *ds* *user-id* "vNew")))))

(deftest disabled-channel-is-skipped
  (testing "channels with enabled=0 don't get polled"
    (enable-mail! *user-id*)
    (db.youtube/upsert-settings *ds* *user-id* {:enabled 1 :polling-minutes 60})
    (db.youtube/add-channel *ds* *user-id*
      {:channel-id "UCbaz" :name nil :min-duration-minutes nil :enabled 0})
    (with-redefs [youtube/get-latest-videos
                  (fn [_] (throw (ex-info "should not be called" {})))]
      (source-worker/run-youtube-tick *ds*))
    (is (empty? (list-inbox)))))

(deftest disabled-source-skips-user
  (testing "youtube_settings.enabled=0 prevents polling that user entirely"
    (enable-mail! *user-id*)
    (db.youtube/upsert-settings *ds* *user-id* {:enabled 0 :polling-minutes 60})
    (db.youtube/add-channel *ds* *user-id*
      {:channel-id "UCqux" :name nil :min-duration-minutes nil :enabled 1})
    (with-redefs [youtube/get-latest-videos
                  (fn [_] (throw (ex-info "should not be called" {})))]
      (source-worker/run-youtube-tick *ds*))
    (is (empty? (list-inbox)))))

(deftest deduplicates-across-ticks
  (testing "running the worker twice does not produce duplicate messages"
    (enable-mail! *user-id*)
    (db.youtube/upsert-settings *ds* *user-id* {:enabled 1 :polling-minutes 0})
    (db.youtube/add-channel *ds* *user-id*
      {:channel-id "UCdup" :name nil :min-duration-minutes nil :enabled 1})
    (with-mocked-fetch [{:video-id "vOnce" :title "Once"
                         :published "2999-02-02T00:00:00Z"
                         :link "https://youtu.be/vOnce" :author "Dup"}]
      (fn []
        (source-worker/run-youtube-tick *ds*)
        (source-worker/run-youtube-tick *ds*)))
    (is (= 1 (count (list-inbox))))))

(deftest polling-cycle-respected
  (testing "users polled within polling_minutes are skipped"
    (enable-mail! *user-id*)
    (db.youtube/upsert-settings *ds* *user-id* {:enabled 1 :polling-minutes 60})
    (db.youtube/add-channel *ds* *user-id*
      {:channel-id "UCcadence" :name nil :min-duration-minutes nil :enabled 1})
    (db.youtube/set-last-polled! *ds* *user-id*)
    (let [calls (atom 0)]
      (with-redefs [youtube/get-latest-videos (fn [_] (swap! calls inc) [])]
        (source-worker/run-youtube-tick *ds*)
        (is (zero? @calls) "user with fresh last_polled_at should be skipped")))))

(deftest min-duration-skips-short-videos
  (testing "videos shorter than min_duration_minutes are not forwarded"
    (enable-mail! *user-id*)
    (db.youtube/upsert-settings *ds* *user-id* {:enabled 1 :polling-minutes 60})
    (db.youtube/add-channel *ds* *user-id*
      {:channel-id "UCshort" :name nil :min-duration-minutes 5 :enabled 1})
    (with-redefs [youtube/get-latest-videos
                  (fn [_] [{:video-id "vShort" :title "Short"
                            :published "2999-03-03T00:00:00Z"
                            :link "https://youtu.be/vShort" :author "Shorty"}])
                  youtube/get-video-duration-minutes (fn [_] 1.0)]
      (source-worker/run-youtube-tick *ds*))
    (is (empty? (list-inbox)))
    (is (db.youtube/video-notified? *ds* *user-id* "vShort"))))

(deftest min-duration-forwards-when-duration-unknown
  (testing "when the duration lookup fails (nil), the video is forwarded anyway — current policy"
    (enable-mail! *user-id*)
    (db.youtube/upsert-settings *ds* *user-id* {:enabled 1 :polling-minutes 60})
    (db.youtube/add-channel *ds* *user-id*
      {:channel-id "UCunknown" :name nil :min-duration-minutes 5 :enabled 1})
    (with-redefs [youtube/get-latest-videos
                  (fn [_] [{:video-id "vUnknown" :title "Unknown length"
                            :published "2999-04-04T00:00:00Z"
                            :link "https://youtu.be/vUnknown" :author "Mystery"}])
                  youtube/get-video-duration-minutes (fn [_] nil)]
      (source-worker/run-youtube-tick *ds*))
    (is (= 1 (count (list-inbox)))
        "duration unknown does not drop the video; making the lookup reliable is what keeps shorts out")
    (is (db.youtube/video-notified? *ds* *user-id* "vUnknown"))))
