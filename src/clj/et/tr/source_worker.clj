(ns et.tr.source-worker
  "Background worker for external sources that fan out into the user's
  inbox. Architecturally lives one level above the database layer: it
  invokes controller-level service functions (currently
  message-handler/add-message!) and never writes the messages table
  directly. New source types plug in alongside the YouTube/Podcast/Atom
  branches."
  (:require [et.tr.db.youtube :as db.youtube]
            [et.tr.db.podcast :as db.podcast]
            [et.tr.db.atom-feed :as db.atom]
            [et.tr.server.message-handler :as message-handler]
            [et.tr.youtube :as youtube]
            [et.tr.podcast :as podcast]
            [et.tr.atom-feed :as atom-feed]
            [et.tr.html-sanitize :as html-sanitize]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.util.concurrent Executors TimeUnit]))

(defn- worker-actor [tag]
  {:actor-user-id nil
   :actor-username (str "system:" tag)
   :is-machine? true
   :parent-user-id nil
   :parent-username nil})

(defn- before? [a b]
  (and a b (neg? (compare a b))))

(defn- forward!
  "Push a message into the user's inbox via the message-handler service.
  Accepts an optional :type (\"markdown\" by default, \"html\" for
  sanitized HTML payloads from feeds that declare type=\"html\")."
  ([ds actor user-id sender title description]
   (forward! ds actor user-id sender title description "markdown"))
  ([ds actor user-id sender title description type]
   (let [result (message-handler/add-message!
                  ds actor user-id
                  {:sender sender
                   :title title
                   :description description
                   :type type
                   :scope "private"})]
     (when (:error result)
       (tel/log! {:level :warn :data {:user-id user-id :sender sender
                                      :error (:error result)}}
                 "Source worker: failed to add inbox message"))
     result)))

;; ── YouTube ────────────────────────────────────────────────────────────

(def ^:private yt-actor (worker-actor "youtube"))

(defn- video-too-short? [video-id min-duration]
  (when min-duration
    (when-let [duration (youtube/get-video-duration-minutes video-id)]
      (< duration min-duration))))

(defn- forward-video! [ds user-id channel video]
  (let [{:keys [author title link]} video
        display-author (or (:name channel) author)]
    (forward! ds yt-actor user-id "YouTube"
              (str "New YouTube video from \"" display-author "\" just dropped.")
              (str "# " title "\n\n" link))
    (tel/log! {:level :info :data {:user-id user-id :video-id (:video-id video)
                                   :title title :channel display-author}}
              "YouTube worker: forwarded video to inbox")))

(defn- process-yt-channel! [ds user-id channel]
  (let [{:keys [channel_id added_at min_duration_minutes]} channel
        videos (youtube/get-latest-videos channel_id)]
    (doseq [{:keys [video-id published] :as video} videos]
      (when (and video-id (not (db.youtube/video-notified? ds user-id video-id)))
        (cond
          (before? published added_at)
          (db.youtube/mark-video-notified! ds user-id video-id)

          (video-too-short? video-id min_duration_minutes)
          (do (tel/log! {:level :info :data {:user-id user-id :video-id video-id
                                             :title (:title video)}}
                        "YouTube worker: skipping short video")
              (db.youtube/mark-video-notified! ds user-id video-id))

          :else
          (do (forward-video! ds user-id channel video)
              (db.youtube/mark-video-notified! ds user-id video-id)))))))

(defn- poll-yt-user! [ds user-id]
  (try
    (let [channels (db.youtube/list-channels ds user-id)
          enabled (filter #(= 1 (:enabled %)) channels)]
      (tel/log! {:level :info :data {:user-id user-id :channels (count enabled)}}
                "YouTube worker: polling user")
      (doseq [channel enabled]
        (try
          (process-yt-channel! ds user-id channel)
          (catch Exception e
            (tel/log! {:level :warn :data {:user-id user-id
                                           :channel (:channel_id channel)
                                           :error (.getMessage e)}}
                      "YouTube worker: channel poll failed"))))
      (db.youtube/set-last-polled! ds user-id))
    (catch Exception e
      (tel/log! {:level :error :data {:user-id user-id :error (.getMessage e)}}
                "YouTube worker: user poll failed"))))

(defn run-youtube-tick [ds]
  (try
    (let [user-ids (db.youtube/users-due-for-poll ds)]
      (tel/log! {:level :info :data {:users (count user-ids)}}
                "YouTube worker: tick start")
      (doseq [user-id user-ids]
        (poll-yt-user! ds user-id)))
    (catch Exception e
      (tel/log! {:level :error :data {:error (.getMessage e)}}
                "YouTube worker: tick failed"))))

;; ── Podcasts ───────────────────────────────────────────────────────────

(def ^:private podcast-actor (worker-actor "podcast"))

(defn- forward-episode! [ds user-id feed episode]
  (let [{:keys [title link]} episode
        display-name (or (:name feed) (:author episode) (:feed_url feed))]
    (forward! ds podcast-actor user-id "Podcasts"
              (str "New podcast episode from \"" display-name "\"")
              (str "# " title "\n\n" link))
    (tel/log! {:level :info :data {:user-id user-id :guid (:guid episode)
                                   :title title :feed display-name}}
              "Podcast worker: forwarded episode to inbox")))

(defn- process-podcast-feed! [ds user-id feed]
  (let [{:keys [feed_url added_at]} feed
        episodes (podcast/get-latest-episodes feed_url)]
    (doseq [{:keys [guid published] :as episode} episodes]
      (when (and guid (not (db.podcast/episode-notified? ds user-id guid)))
        (if (before? published added_at)
          (db.podcast/mark-episode-notified! ds user-id guid)
          (do (forward-episode! ds user-id feed episode)
              (db.podcast/mark-episode-notified! ds user-id guid)))))))

(defn- poll-podcast-user! [ds user-id]
  (try
    (let [feeds (db.podcast/list-feeds ds user-id)
          enabled (filter #(= 1 (:enabled %)) feeds)]
      (tel/log! {:level :info :data {:user-id user-id :feeds (count enabled)}}
                "Podcast worker: polling user")
      (doseq [feed enabled]
        (try
          (process-podcast-feed! ds user-id feed)
          (catch Exception e
            (tel/log! {:level :warn :data {:user-id user-id
                                           :feed (:feed_url feed)
                                           :error (.getMessage e)}}
                      "Podcast worker: feed poll failed"))))
      (db.podcast/set-last-polled! ds user-id))
    (catch Exception e
      (tel/log! {:level :error :data {:user-id user-id :error (.getMessage e)}}
                "Podcast worker: user poll failed"))))

(defn run-podcast-tick [ds]
  (try
    (let [user-ids (db.podcast/users-due-for-poll ds)]
      (tel/log! {:level :info :data {:users (count user-ids)}}
                "Podcast worker: tick start")
      (doseq [user-id user-ids]
        (poll-podcast-user! ds user-id)))
    (catch Exception e
      (tel/log! {:level :error :data {:error (.getMessage e)}}
                "Podcast worker: tick failed"))))

;; ── Atom feeds ─────────────────────────────────────────────────────────

(def ^:private atom-actor (worker-actor "atom"))

(defn- html-payload?
  "Atom entries carry summary/content as typed-payload maps. Treat
  type=\"html\" or type=\"xhtml\" as HTML; everything else as plain text."
  [payload]
  (contains? #{"html" "xhtml"} (:type payload)))

(defn- atom-body
  "Returns [body-string :markdown|:html]. Prefers content when both are
  present. When the chosen payload is HTML, builds a sanitized HTML
  document; otherwise concatenates plain text under a markdown header."
  [{:keys [title link summary content]}]
  (let [payload (or content summary)]
    (cond
      (html-payload? payload)
      [(str "<h2><a href=\"" link "\">" title "</a></h2>\n"
            (html-sanitize/sanitize (:value payload)))
       :html]

      payload
      [(cond-> (str "# " title "\n\n" link)
         (:value payload) (str "\n\n" (str/trim (:value payload))))
       :markdown]

      :else
      [(str "# " title "\n\n" link) :markdown])))

(defn- forward-atom-entry! [ds user-id feed entry]
  (let [{:keys [title]} entry
        display-name (or (:name feed) (:author entry) (:feed_url feed))
        sender (or (:name feed) display-name)
        [body kind] (atom-body entry)]
    (forward! ds atom-actor user-id sender
              (str "New post from \"" display-name "\"")
              body
              (name kind))
    (tel/log! {:level :info :data {:user-id user-id :entry-id (:entry-id entry)
                                   :title title :feed display-name :kind kind}}
              "Atom worker: forwarded entry to inbox")))

(defn- process-atom-feed! [ds user-id feed]
  (let [{:keys [feed_url added_at]} feed
        entries (atom-feed/get-latest-entries feed_url)]
    (doseq [{:keys [entry-id published] :as entry} entries]
      (when (and entry-id (not (db.atom/entry-notified? ds user-id entry-id)))
        (if (before? published added_at)
          (db.atom/mark-entry-notified! ds user-id entry-id)
          (do (forward-atom-entry! ds user-id feed entry)
              (db.atom/mark-entry-notified! ds user-id entry-id)))))))

(defn- poll-atom-user! [ds user-id]
  (try
    (let [feeds (db.atom/list-feeds ds user-id)
          enabled (filter #(= 1 (:enabled %)) feeds)]
      (tel/log! {:level :info :data {:user-id user-id :feeds (count enabled)}}
                "Atom worker: polling user")
      (doseq [feed enabled]
        (try
          (process-atom-feed! ds user-id feed)
          (catch Exception e
            (tel/log! {:level :warn :data {:user-id user-id
                                           :feed (:feed_url feed)
                                           :error (.getMessage e)}}
                      "Atom worker: feed poll failed"))))
      (db.atom/set-last-polled! ds user-id))
    (catch Exception e
      (tel/log! {:level :error :data {:user-id user-id :error (.getMessage e)}}
                "Atom worker: user poll failed"))))

(defn run-atom-tick [ds]
  (try
    (let [user-ids (db.atom/users-due-for-poll ds)]
      (tel/log! {:level :info :data {:users (count user-ids)}}
                "Atom worker: tick start")
      (doseq [user-id user-ids]
        (poll-atom-user! ds user-id)))
    (catch Exception e
      (tel/log! {:level :error :data {:error (.getMessage e)}}
                "Atom worker: tick failed"))))

;; ── Scheduler ──────────────────────────────────────────────────────────

(defn start-scheduler
  "Start the periodic poller. Ticks every 60 seconds; per-user polling
  cadence is gated by each source's polling_minutes."
  [ds]
  (let [scheduler (Executors/newSingleThreadScheduledExecutor)]
    (tel/log! :info "Source worker: scheduling YouTube/Podcast/Atom pollers (60s tick)")
    (.scheduleAtFixedRate scheduler
      ^Runnable (fn []
                  (run-youtube-tick ds)
                  (run-podcast-tick ds)
                  (run-atom-tick ds))
      60
      60
      TimeUnit/SECONDS)
    scheduler))
