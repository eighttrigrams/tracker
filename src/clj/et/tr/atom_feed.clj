(ns et.tr.atom-feed
  "Atom/RSS feed parsing for blog-style sources. Mirrors automator/atom.clj."
  (:require [clojure.xml :as xml]
            [clj-http.client :as http]
            [taoensso.telemere :as tel])
  (:import [java.io ByteArrayInputStream]))

(defn- find-children [element tag]
  (->> (:content element)
       (filter #(= (:tag %) tag))))

(defn- find-child [element tag]
  (first (find-children element tag)))

(defn- text-content [element tag]
  (first (:content (find-child element tag))))

(defn- link-href [entry]
  (or (->> (find-children entry :link)
           (filter #(or (= (get-in % [:attrs :rel]) "alternate")
                        (nil? (get-in % [:attrs :rel]))))
           first :attrs :href)
      (get-in (find-child entry :link) [:attrs :href])))

(defn- typed-payload
  "Returns {:type \"text\"|\"html\"|\"xhtml\" :value str} for the named
  child of an atom entry, or nil when the child is absent. The atom RFC
  defines this `type` attribute; we default to \"text\" when omitted."
  [entry tag]
  (when-let [el (find-child entry tag)]
    (when-let [value (first (:content el))]
      {:type  (or (get-in el [:attrs :type]) "text")
       :value value})))

(defn- parse-entries [xml-str]
  (let [parsed (xml/parse (ByteArrayInputStream. (.getBytes xml-str "UTF-8")))]
    (->> (find-children parsed :entry)
         (mapv (fn [entry]
                 {:entry-id  (text-content entry :id)
                  :title     (text-content entry :title)
                  :published (or (text-content entry :published)
                                 (text-content entry :updated))
                  :link      (link-href entry)
                  :author    (text-content (find-child entry :author) :name)
                  :summary   (typed-payload entry :summary)
                  :content   (typed-payload entry :content)})))))

(defn get-latest-entries
  "Fetch and parse an atom feed. Returns a vector of entry maps, or nil
  on network/parse failure."
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
                "Failed to fetch atom feed")
      nil)))
