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
