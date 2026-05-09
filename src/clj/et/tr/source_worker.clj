(ns et.tr.source-worker
  "Background worker for external sources that fan out into the user's
  inbox. Architecturally lives one level above the database layer: it
  invokes controller-level service functions (currently
  message-handler/add-message!) and never writes the messages table
  directly. New source types plug in alongside the YouTube branch."
  (:require [et.tr.db.youtube :as db.youtube]
            [et.tr.server.message-handler :as message-handler]
            [et.tr.youtube :as youtube]
            [taoensso.telemere :as tel])
  (:import [java.util.concurrent Executors TimeUnit]))

(def ^:private worker-actor
  "Synthetic actor used when the worker writes events. Marks the entry
  as machine-driven so audit history can distinguish worker writes from
  human ones."
  {:actor-user-id nil
   :actor-username "system:youtube"
   :is-machine? true
   :parent-user-id nil
   :parent-username nil})

(defn- video-too-short? [api-key video-id min-duration]
  (when (and min-duration api-key)
    (when-let [duration (youtube/get-video-duration-minutes api-key video-id)]
      (< duration min-duration))))

(defn- before? [a b]
  (and a b (neg? (compare a b))))

(defn- forward-video! [ds user-id channel video]
  (let [{:keys [author title link]} video
        sender "YouTube"
        display-author (or (:name channel) author)
        result (message-handler/add-message!
                 ds worker-actor user-id
                 {:sender sender
                  :title (str "New YouTube video from \"" display-author "\" just dropped.")
                  :description (str "# " title "\n\n" link)
                  :type "markdown"
                  :scope "private"})]
    (if (:error result)
      (tel/log! {:level :warn :data {:user-id user-id :video-id (:video-id video)
                                     :error (:error result)}}
                "YouTube worker: failed to add inbox message")
      (tel/log! {:level :info :data {:user-id user-id :video-id (:video-id video)
                                     :title title :channel display-author}}
                "YouTube worker: forwarded video to inbox"))))

(defn- process-channel! [ds api-key user-id channel]
  (let [{:keys [channel_id added_at min_duration_minutes]} channel
        videos (youtube/get-latest-videos channel_id)]
    (doseq [{:keys [video-id published] :as video} videos]
      (when (and video-id (not (db.youtube/video-notified? ds user-id video-id)))
        (cond
          (before? published added_at)
          (db.youtube/mark-video-notified! ds user-id video-id)

          (video-too-short? api-key video-id min_duration_minutes)
          (do (tel/log! {:level :info :data {:user-id user-id :video-id video-id
                                             :title (:title video)}}
                        "YouTube worker: skipping short video")
              (db.youtube/mark-video-notified! ds user-id video-id))

          :else
          (do (forward-video! ds user-id channel video)
              (db.youtube/mark-video-notified! ds user-id video-id)))))))

(defn- poll-user! [ds api-key user-id]
  (try
    (let [channels (db.youtube/list-channels ds user-id)
          enabled (filter #(= 1 (:enabled %)) channels)]
      (tel/log! {:level :info :data {:user-id user-id :channels (count enabled)}}
                "YouTube worker: polling user")
      (doseq [channel enabled]
        (try
          (process-channel! ds api-key user-id channel)
          (catch Exception e
            (tel/log! {:level :warn :data {:user-id user-id
                                           :channel (:channel_id channel)
                                           :error (.getMessage e)}}
                      "YouTube worker: channel poll failed"))))
      (db.youtube/set-last-polled! ds user-id))
    (catch Exception e
      (tel/log! {:level :error :data {:user-id user-id :error (.getMessage e)}}
                "YouTube worker: user poll failed"))))

(defn run-youtube-tick
  "One scheduler tick: poll every user whose YouTube source is enabled and
  whose polling cycle has elapsed. The api-key is passed explicitly so
  tests can supply their own; production callers pass the env var."
  ([ds] (run-youtube-tick ds (System/getenv "YOUTUBE_API_KEY")))
  ([ds api-key]
   (try
     (let [user-ids (db.youtube/users-due-for-poll ds)]
       (when (seq user-ids)
         (tel/log! {:level :info :data {:users (count user-ids)}}
                   "YouTube worker: tick start"))
       (doseq [user-id user-ids]
         (poll-user! ds api-key user-id)))
     (catch Exception e
       (tel/log! {:level :error :data {:error (.getMessage e)}}
                 "YouTube worker: tick failed")))))

(defn start-scheduler
  "Start the periodic YouTube poller. Ticks every 60 seconds; per-user
  polling cadence is gated by youtube_settings.polling_minutes."
  [ds]
  (let [scheduler (Executors/newSingleThreadScheduledExecutor)]
    (tel/log! :info "Source worker: scheduling YouTube poller (60s tick)")
    (.scheduleAtFixedRate scheduler
      ^Runnable (fn [] (run-youtube-tick ds))
      60
      60
      TimeUnit/SECONDS)
    scheduler))
