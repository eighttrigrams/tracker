(ns et.tr.youtube
  "YouTube channel feed parsing and video duration lookups. Mirrors the
  same surface as automator/youtube.clj but uses clj-http (already a
  tracker dependency)."
  (:require [clojure.xml :as xml]
            [clj-http.client :as http]
            [taoensso.telemere :as tel])
  (:import [java.io ByteArrayInputStream]))

(def ^:private feed-url "https://www.youtube.com/feeds/videos.xml?channel_id=")

(defn- find-child [entry tag]
  (->> (:content entry)
       (filter #(= (:tag %) tag))
       first))

(defn- parse-entries [xml-str]
  (let [parsed (xml/parse (ByteArrayInputStream. (.getBytes xml-str "UTF-8")))]
    (->> (:content parsed)
         (filter #(= (:tag %) :entry))
         (mapv (fn [entry]
                 (let [video-id (first (:content (find-child entry :yt:videoId)))
                       title (first (:content (find-child entry :title)))
                       published (first (:content (find-child entry :published)))
                       link (-> (find-child entry :link) :attrs :href)
                       author (first (:content (find-child (find-child entry :author) :name)))]
                   {:video-id video-id
                    :title title
                    :published published
                    :link link
                    :author author}))))))

(defn get-latest-videos
  "Fetch and parse the public YouTube videos.xml feed for a channel.
  Returns a vector of entry maps, or nil on network/parse failure."
  [channel-id]
  (try
    (let [resp (http/get (str feed-url channel-id)
                 {:as :string
                  :throw-exceptions false
                  :socket-timeout 30000
                  :connection-timeout 30000})]
      (when (= 200 (:status resp))
        (parse-entries (:body resp))))
    (catch Exception e
      (tel/log! {:level :warn :data {:channel-id channel-id :error (.getMessage e)}}
                "Failed to fetch YouTube feed")
      nil)))

(defn- parse-iso8601-duration [duration-str]
  (let [[_ h m s] (re-find #"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?" duration-str)
        hours (if h (parse-long h) 0)
        minutes (if m (parse-long m) 0)
        seconds (if s (parse-long s) 0)]
    (+ (* hours 60) minutes (/ seconds 60.0))))

(defn get-video-duration-minutes
  "Look up the duration (in minutes, decimal) of a video via the YouTube
  Data API. Returns nil if the API key is missing, the request fails,
  or the response shape is unexpected."
  [api-key video-id]
  (when (and api-key video-id)
    (try
      (let [resp (http/get "https://www.googleapis.com/youtube/v3/videos"
                   {:query-params {:part "contentDetails"
                                   :id video-id
                                   :key api-key}
                    :as :json
                    :throw-exceptions false
                    :socket-timeout 30000
                    :connection-timeout 30000})]
        (when (= 200 (:status resp))
          (when-let [duration (-> resp :body :items first :contentDetails :duration)]
            (parse-iso8601-duration duration))))
      (catch Exception e
        (tel/log! {:level :warn :data {:video-id video-id :error (.getMessage e)}}
                  "Failed to fetch YouTube video duration")
        nil))))
