(ns et.tr.podcast
  "Podcast RSS feed parsing. Mirrors automator/podcast.clj but uses
  clj-http (already a tracker dependency)."
  (:require [clojure.xml :as xml]
            [clj-http.client :as http]
            [taoensso.telemere :as tel])
  (:import [java.io ByteArrayInputStream]
           [java.time ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(defn- find-children [element tag]
  (->> (:content element)
       (filter #(= (:tag %) tag))))

(defn- find-child [element tag]
  (first (find-children element tag)))

(defn- text-content [element tag]
  (first (:content (find-child element tag))))

(defn- normalize-date [date-str]
  (try
    (str (.toInstant (ZonedDateTime/parse date-str DateTimeFormatter/RFC_1123_DATE_TIME)))
    (catch Exception _ date-str)))

(defn- parse-entries [xml-str]
  (let [parsed (xml/parse (ByteArrayInputStream. (.getBytes xml-str "UTF-8")))
        channel (find-child parsed :channel)]
    (->> (find-children channel :item)
         (mapv (fn [item]
                 {:guid (or (text-content item :guid)
                            (text-content item :link))
                  :title (text-content item :title)
                  :published (normalize-date (or (text-content item :pubDate)
                                                 (text-content item :dc:date)
                                                 ""))
                  :link (text-content item :link)
                  :author (or (text-content item :itunes:author)
                              (text-content channel :itunes:author))})))))

(defn get-latest-episodes
  "Fetch and parse a podcast RSS feed. Returns a vector of episode maps,
  or nil on network/parse failure."
  [feed-url]
  (try
    (let [resp (http/get feed-url
                 {:as :string
                  :throw-exceptions false
                  :socket-timeout 30000
                  :connection-timeout 30000})]
      (when (= 200 (:status resp))
        (parse-entries (:body resp))))
    (catch Exception e
      (tel/log! {:level :warn :data {:feed-url feed-url :error (.getMessage e)}}
                "Failed to fetch podcast feed")
      nil)))
