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

(def ^:private watch-page-headers
  {;; A real browser UA + en locale, plus a consent cookie, so EU/consent
   ;; regions get the actual watch page rather than the consent interstitial
   ;; (which carries none of the duration fields).
   "User-Agent" "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
   "Accept-Language" "en-US,en;q=0.9"
   "Cookie" "CONSENT=YES+cb; SOCS=CAI"})

(defn- iso8601-duration->minutes [s]
  (when-let [[_ h m sec] (re-find #"^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$" s)]
    (let [h (or (some-> h parse-long) 0)
          m (or (some-> m parse-long) 0)
          sec (or (some-> sec parse-long) 0)
          total (+ (* h 60) m (/ sec 60.0))]
      (when (pos? total) total))))

(defn parse-duration-minutes
  "Extract a video's duration in decimal minutes from a watch-page body.
  Tries `videoDetails.lengthSeconds` first, then `approxDurationMs` from
  streamingData, then the schema.org `<meta itemprop=\"duration\">`
  ISO-8601 value (an independent source that survives player-payload shape
  changes). Treats a zero/blank value as unknown (live streams and
  premieres report 0). Returns nil when no usable duration is present."
  [body]
  (when body
    (or (when-let [[_ secs] (re-find #"\"lengthSeconds\":\"(\d+)\"" body)]
          (when-let [s (parse-long secs)]
            (when (pos? s) (/ s 60.0))))
        (when-let [[_ ms] (re-find #"\"approxDurationMs\":\"(\d+)\"" body)]
          (when-let [m (parse-long ms)]
            (when (pos? m) (/ m 60000.0))))
        (when-let [[_ pt] (re-find #"itemprop=\"duration\"[^>]*content=\"(PT[0-9HMS]+)\"" body)]
          (iso8601-duration->minutes pt)))))

(defn- consent-interstitial?
  [body]
  (boolean (and body
                (re-find #"consent\.(?:youtube|google)\.com" body)
                (not (re-find #"ytInitialPlayerResponse" body)))))

(defn get-video-duration-minutes
  "Look up the duration (in minutes, decimal) of a video by scraping the
  public watch page. Keyless. Retries once on a failed/empty fetch since
  the page is occasionally served without the player payload. Returns nil
  only if both attempts fail or no duration field can be found; the reason
  (non-200, consent interstitial, or a 200 page with no duration field) is
  logged distinctly so a recurring leak can be diagnosed from the logs."
  [video-id]
  (when video-id
    (letfn [(attempt []
              (try
                (let [resp (http/get (str "https://www.youtube.com/watch?v=" video-id "&hl=en")
                             {:as :string
                              :throw-exceptions false
                              :socket-timeout 30000
                              :connection-timeout 30000
                              :headers watch-page-headers})]
                  (if (= 200 (:status resp))
                    (let [body (:body resp)]
                      (or (parse-duration-minutes body)
                          (do (if (consent-interstitial? body)
                                (tel/log! {:level :warn :data {:video-id video-id}}
                                          "YouTube duration: served a consent interstitial, no player payload")
                                (tel/log! {:level :warn :data {:video-id video-id}}
                                          "YouTube duration: 200 OK but no duration field found in page"))
                              nil)))
                    (do (tel/log! {:level :warn :data {:video-id video-id :status (:status resp)}}
                                  "YouTube duration: non-200 response")
                        nil)))
                (catch Exception e
                  (tel/log! {:level :warn :data {:video-id video-id :error (.getMessage e)}}
                            "Failed to fetch YouTube video duration")
                  nil)))]
      (or (attempt)
          (attempt)))))
