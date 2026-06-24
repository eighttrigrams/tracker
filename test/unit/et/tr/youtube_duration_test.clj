(ns et.tr.youtube-duration-test
  "Unit coverage for parsing a video's duration out of a watch-page body —
  the core of the YouTube min-minutes filter. Network fetching is not
  exercised here; only the extraction is."
  (:require [clojure.test :refer [deftest is testing]]
            [et.tr.youtube :as youtube]))

(deftest parses-length-seconds
  (testing "lengthSeconds is read as decimal minutes"
    (is (= 3.0 (youtube/parse-duration-minutes "...\"lengthSeconds\":\"180\"...")))
    (is (= 2.5 (youtube/parse-duration-minutes "\"lengthSeconds\":\"150\"")))))

(deftest falls-back-to-approx-duration-ms
  (testing "when lengthSeconds is absent, approxDurationMs (ms) is used"
    (is (= 3.0 (youtube/parse-duration-minutes "\"approxDurationMs\":\"180000\"")))))

(deftest prefers-length-seconds-over-approx
  (is (= 1.0 (youtube/parse-duration-minutes
               "\"lengthSeconds\":\"60\" ... \"approxDurationMs\":\"999000\""))))

(deftest zero-length-is-unknown
  (testing "live streams/premieres report 0 — treated as unknown so the filter falls back to approx, then nil"
    (is (nil? (youtube/parse-duration-minutes "\"lengthSeconds\":\"0\"")))
    (is (= 5.0 (youtube/parse-duration-minutes
                 "\"lengthSeconds\":\"0\" ... \"approxDurationMs\":\"300000\"")))))

(deftest missing-fields-yield-nil
  (is (nil? (youtube/parse-duration-minutes "<html>consent interstitial</html>")))
  (is (nil? (youtube/parse-duration-minutes "")))
  (is (nil? (youtube/parse-duration-minutes nil))))

(def ^:private realistic-watch-page
  "A trimmed body that mirrors the real watch-page shape: ytInitialData
  (related videos, no lengthSeconds) appears before ytInitialPlayerResponse,
  streamingData/approxDurationMs precedes videoDetails/lengthSeconds, and
  URLs carry the escaped forward slashes YouTube emits. The video is 212s."
  (str "<!DOCTYPE html><html><head><title>Clip</title>"
       "<meta itemprop=\"duration\" content=\"PT3M32S\">"
       "<script nonce=\"a\">var ytInitialData = {\"contents\":{\"twoColumnWatchNextResults\":"
       "{\"secondaryResults\":{\"results\":[{\"compactVideoRenderer\":{\"videoId\":\"rel1\","
       "\"lengthText\":{\"simpleText\":\"4:13\"}}}]}}}};</script>"
       "<script nonce=\"b\">var ytInitialPlayerResponse = {\"responseContext\":{},"
       "\"playabilityStatus\":{\"status\":\"OK\"},"
       "\"streamingData\":{\"expiresInSeconds\":\"21540\",\"formats\":[{\"itag\":18,"
       "\"url\":\"https:\\/\\/rr3---sn-abc.googlevideo.com\\/videoplayback\","
       "\"approxDurationMs\":\"212000\"}]},"
       "\"videoDetails\":{\"videoId\":\"xyz\",\"title\":\"Clip\","
       "\"lengthSeconds\":\"212\",\"channelId\":\"UCabc\"}};</script></head><body></body></html>"))

(deftest parses-representative-watch-page
  (testing "a real-structure watch page yields the videoDetails duration"
    (is (= (/ 212 60.0) (youtube/parse-duration-minutes realistic-watch-page))
        "212 seconds = ~3.53 minutes, read from videoDetails.lengthSeconds")))

(deftest meta-itemprop-duration-fallback
  (testing "schema.org <meta itemprop=duration> ISO-8601 is used when the player payload is absent"
    (is (< 4.21 (youtube/parse-duration-minutes
                  "<meta itemprop=\"duration\" content=\"PT4M13S\">") 4.22))
    (is (= 90.0 (youtube/parse-duration-minutes
                  "<meta itemprop=\"duration\" content=\"PT1H30M\">")))
    (is (= 0.75 (youtube/parse-duration-minutes
                  "<meta itemprop=\"duration\" content=\"PT45S\">"))))
  (testing "player payload still wins over the meta tag"
    (is (= 1.0 (youtube/parse-duration-minutes
                 "\"lengthSeconds\":\"60\" <meta itemprop=\"duration\" content=\"PT99M\">")))))
