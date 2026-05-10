(ns et.tr.server.source-handler
  (:require [clojure.string :as str]
            [et.tr.server.common :as common]
            [et.tr.db.youtube :as db.youtube]
            [et.tr.db.podcast :as db.podcast]
            [et.tr.db.atom-feed :as db.atom]
            [et.tr.youtube :as youtube]))

(defn- mail-only-guard [req f]
  (if (common/has-mail? req)
    (f (common/get-user-id req))
    {:status 403 :body {:error "Mail access required"}}))

(defn- bool->int [v]
  (cond (boolean? v) (if v 1 0)
        (number? v) (if (zero? v) 0 1)
        :else nil))

(defn- present-settings [row]
  (when row
    {:enabled (= 1 (:enabled row))
     :polling_minutes (:polling_minutes row)
     :last_polled_at (:last_polled_at row)}))

(defn- present-channel [row]
  (when row
    (-> row
        (select-keys [:id :channel_id :name :min_duration_minutes :added_at])
        (assoc :enabled (= 1 (:enabled row))))))

(defn- present-feed [row]
  (when row
    (-> row
        (select-keys [:id :feed_url :name :added_at])
        (assoc :enabled (= 1 (:enabled row))))))

(defn- parse-positive-int [v]
  (when (some? v)
    (try (let [n (Integer/parseInt (str v))]
           (when (pos? n) n))
         (catch Exception _ nil))))

;; ── YouTube ────────────────────────────────────────────────────────────

(defn get-youtube-settings-handler
  "GET /api/sources/youtube/settings — return the caller's YouTube polling
  settings as {:enabled :polling_minutes :last_polled_at}, or null when no row
  exists yet. Requires mail access; returns 403 otherwise."
  [req]
  (mail-only-guard req
    (fn [user-id]
      {:status 200 :body (present-settings (db.youtube/get-settings (common/ensure-ds) user-id))})))

(defn put-youtube-settings-handler
  "PUT /api/sources/youtube/settings — upsert the caller's YouTube polling
  settings. Body fields: :enabled (bool/0/1) and :polling_minutes (positive
  int). Returns 400 when polling_minutes is present but not a positive integer,
  403 without mail access, else 200 with the saved settings."
  [req]
  (mail-only-guard req
    (fn [user-id]
      (let [{:keys [enabled polling_minutes]} (:body req)
            polling-minutes (when polling_minutes
                              (try (Integer/parseInt (str polling_minutes))
                                   (catch Exception _ nil)))]
        (cond
          (and (some? polling_minutes) (or (nil? polling-minutes) (< polling-minutes 1)))
          {:status 400 :body {:error "polling_minutes must be a positive integer"}}

          :else
          (let [updated (db.youtube/upsert-settings
                          (common/ensure-ds) user-id
                          {:enabled (bool->int enabled)
                           :polling-minutes polling-minutes})]
            {:status 200 :body (present-settings updated)}))))))

(defn list-youtube-channels-handler
  "GET /api/sources/youtube/channels — list the caller's tracked YouTube
  channels as a vector of {:id :channel_id :name :min_duration_minutes :added_at
  :enabled} maps. Requires mail access; returns 403 otherwise."
  [req]
  (mail-only-guard req
    (fn [user-id]
      {:status 200 :body (mapv present-channel
                               (db.youtube/list-channels (common/ensure-ds) user-id))})))

(defn- resolve-channel-input
  "Accept either a YouTube channel id (e.g. UCxxxx) or a video URL and
  return {:channel-id … :author …}. Returns nil when input is a URL we
  cannot resolve. Author is only populated when derived via scrape."
  [raw]
  (cond
    (str/blank? raw) nil
    (re-find #"^https?://" raw) (youtube/get-channel-info-from-video-url raw)
    :else {:channel-id raw}))

(defn add-youtube-channel-handler
  "POST /api/sources/youtube/channels — track a new YouTube channel for the
  caller. Body fields: :channel_id (required, non-blank — accepts either
  a YouTube channel id or a video URL, in which case the channel is
  derived from the video page), :name (optional; auto-filled from the
  video's owner channel name when a URL is supplied and :name is blank),
  :min_duration_minutes (optional int), :enabled (optional bool, defaults
  true). Returns 400 on missing/invalid fields (or when a URL cannot be
  resolved), 409 when already tracked, 403 without mail access, else 201
  with the created channel."
  [req]
  (mail-only-guard req
    (fn [user-id]
      (let [{:keys [channel_id name min_duration_minutes enabled]} (:body req)
            raw-input (some-> channel_id str str/trim)
            min-mins (when min_duration_minutes
                       (try (Integer/parseInt (str min_duration_minutes))
                            (catch Exception _ nil)))]
        (cond
          (str/blank? raw-input)
          {:status 400 :body {:error "channel_id is required"}}

          (and (some? min_duration_minutes) (nil? min-mins))
          {:status 400 :body {:error "min_duration_minutes must be an integer"}}

          :else
          (let [{:keys [channel-id author]} (or (resolve-channel-input raw-input) {})]
            (cond
              (str/blank? channel-id)
              {:status 400 :body {:error "Could not resolve a YouTube channel from the supplied input"}}

              :else
              (try
                (db.youtube/ensure-settings! (common/ensure-ds) user-id)
                (let [resolved-name (cond
                                      (and (string? name) (not (str/blank? name))) (str/trim name)
                                      (and author (not (str/blank? author))) author
                                      :else nil)
                      created (db.youtube/add-channel
                                (common/ensure-ds) user-id
                                {:channel-id channel-id
                                 :name resolved-name
                                 :min-duration-minutes min-mins
                                 :enabled (bool->int (if (nil? enabled) true enabled))})]
                  {:status 201 :body (present-channel created)})
                (catch Exception e
                  (if (re-find #"UNIQUE" (or (.getMessage e) ""))
                    {:status 409 :body {:error "Channel already added for this user"}}
                    (throw e)))))))))))

(defn update-youtube-channel-handler
  "PUT /api/sources/youtube/channels/:id — update mutable fields on one of the
  caller's tracked channels. Body fields (all optional, only provided keys
  change): :name, :min_duration_minutes (int or blank for null), :enabled.
  Returns 400 when min_duration_minutes is malformed or no fields are provided,
  404 if the channel is not owned/found, 403 without mail access, else 200."
  [req]
  (mail-only-guard req
    (fn [user-id]
      (let [channel-row-id (Integer/parseInt (get-in req [:params :id]))
            body (:body req)
            min-mins-raw (:min_duration_minutes body)
            min-mins (cond
                       (nil? min-mins-raw) nil
                       (string? min-mins-raw)
                       (cond
                         (str/blank? min-mins-raw) nil
                         :else (try (Integer/parseInt min-mins-raw)
                                    (catch Exception _ ::invalid)))
                       (number? min-mins-raw) (int min-mins-raw)
                       :else ::invalid)
            fields (cond-> {}
                     (contains? body :name)
                     (assoc :name (let [n (:name body)]
                                    (when-not (str/blank? n) (str/trim n))))
                     (contains? body :min_duration_minutes)
                     (assoc :min-duration-minutes min-mins)
                     (contains? body :enabled)
                     (assoc :enabled (bool->int (:enabled body))))]
        (cond
          (= ::invalid min-mins)
          {:status 400 :body {:error "min_duration_minutes must be an integer"}}

          (empty? fields)
          {:status 400 :body {:error "No updatable fields provided"}}

          :else
          (if-let [updated (db.youtube/update-channel (common/ensure-ds) user-id channel-row-id fields)]
            {:status 200 :body (present-channel updated)}
            {:status 404 :body {:error "Channel not found"}}))))))

(defn delete-youtube-channel-handler
  "DELETE /api/sources/youtube/channels/:id — stop tracking the given channel
  for the caller. Returns {:success true} on 200, {:success false :error ...}
  on 404 if the channel is not owned/found, 403 without mail access."
  [req]
  (mail-only-guard req
    (fn [user-id]
      (let [channel-row-id (Integer/parseInt (get-in req [:params :id]))
            result (db.youtube/delete-channel (common/ensure-ds) user-id channel-row-id)]
        (if (:success result)
          {:status 200 :body {:success true}}
          {:status 404 :body {:success false :error "Channel not found"}})))))

;; ── Generic feed-style sources (podcasts, atom) ────────────────────────

(defn- get-feed-settings-handler [db-ns]
  (fn [req]
    (mail-only-guard req
      (fn [user-id]
        {:status 200 :body (present-settings ((:get-settings db-ns)
                                              (common/ensure-ds) user-id))}))))

(defn- put-feed-settings-handler [db-ns]
  (fn [req]
    (mail-only-guard req
      (fn [user-id]
        (let [{:keys [enabled polling_minutes]} (:body req)
              polling-minutes (parse-positive-int polling_minutes)]
          (cond
            (and (some? polling_minutes) (nil? polling-minutes))
            {:status 400 :body {:error "polling_minutes must be a positive integer"}}

            :else
            (let [updated ((:upsert-settings db-ns)
                           (common/ensure-ds) user-id
                           {:enabled (bool->int enabled)
                            :polling-minutes polling-minutes})]
              {:status 200 :body (present-settings updated)})))))))

(defn- list-feeds-handler [db-ns]
  (fn [req]
    (mail-only-guard req
      (fn [user-id]
        {:status 200 :body (mapv present-feed
                                 ((:list-feeds db-ns) (common/ensure-ds) user-id))}))))

(defn- add-feed-handler [db-ns]
  (fn [req]
    (mail-only-guard req
      (fn [user-id]
        (let [{:keys [feed_url name enabled]} (:body req)
              feed-url (some-> feed_url str str/trim)]
          (cond
            (str/blank? feed-url)
            {:status 400 :body {:error "feed_url is required"}}

            :else
            (try
              ((:ensure-settings! db-ns) (common/ensure-ds) user-id)
              (let [created ((:add-feed db-ns)
                             (common/ensure-ds) user-id
                             {:feed-url feed-url
                              :name (when-not (str/blank? name) (str/trim name))
                              :enabled (bool->int (if (nil? enabled) true enabled))})]
                {:status 201 :body (present-feed created)})
              (catch Exception e
                (if (re-find #"UNIQUE" (or (.getMessage e) ""))
                  {:status 409 :body {:error "Feed already added for this user"}}
                  (throw e))))))))))

(defn- update-feed-handler [db-ns]
  (fn [req]
    (mail-only-guard req
      (fn [user-id]
        (let [feed-row-id (Integer/parseInt (get-in req [:params :id]))
              body (:body req)
              fields (cond-> {}
                       (contains? body :name)
                       (assoc :name (let [n (:name body)]
                                      (when-not (str/blank? n) (str/trim n))))
                       (contains? body :enabled)
                       (assoc :enabled (bool->int (:enabled body))))]
          (cond
            (empty? fields)
            {:status 400 :body {:error "No updatable fields provided"}}

            :else
            (if-let [updated ((:update-feed db-ns) (common/ensure-ds) user-id feed-row-id fields)]
              {:status 200 :body (present-feed updated)}
              {:status 404 :body {:error "Feed not found"}})))))))

(defn- delete-feed-handler [db-ns]
  (fn [req]
    (mail-only-guard req
      (fn [user-id]
        (let [feed-row-id (Integer/parseInt (get-in req [:params :id]))
              result ((:delete-feed db-ns) (common/ensure-ds) user-id feed-row-id)]
          (if (:success result)
            {:status 200 :body {:success true}}
            {:status 404 :body {:success false :error "Feed not found"}}))))))

(def ^:private podcast-ns
  {:get-settings db.podcast/get-settings
   :upsert-settings db.podcast/upsert-settings
   :ensure-settings! db.podcast/ensure-settings!
   :list-feeds db.podcast/list-feeds
   :add-feed db.podcast/add-feed
   :update-feed db.podcast/update-feed
   :delete-feed db.podcast/delete-feed})

(def ^:private atom-ns
  {:get-settings db.atom/get-settings
   :upsert-settings db.atom/upsert-settings
   :ensure-settings! db.atom/ensure-settings!
   :list-feeds db.atom/list-feeds
   :add-feed db.atom/add-feed
   :update-feed db.atom/update-feed
   :delete-feed db.atom/delete-feed})

(def get-podcast-settings-handler
  "GET /api/sources/podcast/settings — return the caller's podcast polling
  settings as {:enabled :polling_minutes :last_polled_at}, or null when no row
  exists yet. Requires mail access; returns 403 otherwise."
  (get-feed-settings-handler  podcast-ns))

(def put-podcast-settings-handler
  "PUT /api/sources/podcast/settings — upsert the caller's podcast polling
  settings. Body fields: :enabled (bool/0/1) and :polling_minutes (positive
  int). Returns 400 when polling_minutes is present but not a positive integer,
  403 without mail access, else 200 with the saved settings."
  (put-feed-settings-handler  podcast-ns))

(def list-podcast-feeds-handler
  "GET /api/sources/podcast/feeds — list the caller's tracked podcast feeds as
  a vector of {:id :feed_url :name :added_at :enabled} maps. Requires mail
  access; returns 403 otherwise."
  (list-feeds-handler         podcast-ns))

(def add-podcast-feed-handler
  "POST /api/sources/podcast/feeds — track a new podcast feed for the caller.
  Body fields: :feed_url (required, non-blank), :name (optional), :enabled
  (optional bool, defaults true). Returns 400 when feed_url is missing, 409
  when already tracked, 403 without mail access, else 201 with the created
  feed."
  (add-feed-handler           podcast-ns))

(def update-podcast-feed-handler
  "PUT /api/sources/podcast/feeds/:id — update mutable fields on one of the
  caller's tracked podcast feeds. Body fields (all optional, only provided
  keys change): :name, :enabled. Returns 400 when no fields are provided, 404
  if the feed is not owned/found, 403 without mail access, else 200."
  (update-feed-handler        podcast-ns))

(def delete-podcast-feed-handler
  "DELETE /api/sources/podcast/feeds/:id — stop tracking the given podcast
  feed for the caller. Returns {:success true} on 200, {:success false
  :error ...} on 404 if the feed is not owned/found, 403 without mail access."
  (delete-feed-handler        podcast-ns))

(def get-atom-settings-handler
  "GET /api/sources/atom/settings — return the caller's Atom/RSS polling
  settings as {:enabled :polling_minutes :last_polled_at}, or null when no row
  exists yet. Requires mail access; returns 403 otherwise."
  (get-feed-settings-handler  atom-ns))

(def put-atom-settings-handler
  "PUT /api/sources/atom/settings — upsert the caller's Atom/RSS polling
  settings. Body fields: :enabled (bool/0/1) and :polling_minutes (positive
  int). Returns 400 when polling_minutes is present but not a positive integer,
  403 without mail access, else 200 with the saved settings."
  (put-feed-settings-handler  atom-ns))

(def list-atom-feeds-handler
  "GET /api/sources/atom/feeds — list the caller's tracked Atom/RSS feeds as
  a vector of {:id :feed_url :name :added_at :enabled} maps. Requires mail
  access; returns 403 otherwise."
  (list-feeds-handler         atom-ns))

(def add-atom-feed-handler
  "POST /api/sources/atom/feeds — track a new Atom/RSS feed for the caller.
  Body fields: :feed_url (required, non-blank), :name (optional), :enabled
  (optional bool, defaults true). Returns 400 when feed_url is missing, 409
  when already tracked, 403 without mail access, else 201 with the created
  feed."
  (add-feed-handler           atom-ns))

(def update-atom-feed-handler
  "PUT /api/sources/atom/feeds/:id — update mutable fields on one of the
  caller's tracked Atom/RSS feeds. Body fields (all optional, only provided
  keys change): :name, :enabled. Returns 400 when no fields are provided, 404
  if the feed is not owned/found, 403 without mail access, else 200."
  (update-feed-handler        atom-ns))

(def delete-atom-feed-handler
  "DELETE /api/sources/atom/feeds/:id — stop tracking the given Atom/RSS feed
  for the caller. Returns {:success true} on 200, {:success false :error ...}
  on 404 if the feed is not owned/found, 403 without mail access."
  (delete-feed-handler        atom-ns))
