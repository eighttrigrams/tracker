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

(defn get-channel-info-from-video-url
  "Scrape the YouTube watch page for the channel that owns the video.
  Returns {:channel-id … :author …} or nil. Keyless."
  [video-url]
  (when video-url
    (try
      (let [resp (http/get video-url
                   {:as :string
                    :throw-exceptions false
                    :socket-timeout 30000
                    :connection-timeout 30000
                    :headers {"User-Agent" "Mozilla/5.0"}})]
        (when (= 200 (:status resp))
          (let [body (:body resp)
                channel-id (second (re-find #"\"channelId\":\"(UC[\w-]+)\"" body))
                author (second (re-find #"\"ownerChannelName\":\"([^\"]+)\"" body))]
            (when channel-id
              {:channel-id channel-id :author author}))))
      (catch Exception e
        (tel/log! {:level :warn :data {:url video-url :error (.getMessage e)}}
                  "Failed to fetch YouTube channel info from video URL")
        nil))))

(defn get-video-duration-minutes
  "Look up the duration (in minutes, decimal) of a video by scraping
  `lengthSeconds` from the public watch page. Keyless. Returns nil if
  the request fails or the field cannot be found."
  [video-id]
  (when video-id
    (try
      (let [resp (http/get (str "https://www.youtube.com/watch?v=" video-id)
                   {:as :string
                    :throw-exceptions false
                    :socket-timeout 30000
                    :connection-timeout 30000
                    :headers {"User-Agent" "Mozilla/5.0"}})]
        (when (= 200 (:status resp))
          (when-let [[_ secs] (re-find #"\"lengthSeconds\":\"(\d+)\"" (:body resp))]
            (/ (parse-long secs) 60.0))))
      (catch Exception e
        (tel/log! {:level :warn :data {:video-id video-id :error (.getMessage e)}}
                  "Failed to fetch YouTube video duration")
        nil))))
