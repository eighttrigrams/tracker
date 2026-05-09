(ns et.tr.server.source-handler
  (:require [clojure.string :as str]
            [et.tr.server.common :as common]
            [et.tr.db.youtube :as db.youtube]))

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

(defn get-youtube-settings-handler [req]
  (mail-only-guard req
    (fn [user-id]
      {:status 200 :body (present-settings (db.youtube/get-settings (common/ensure-ds) user-id))})))

(defn put-youtube-settings-handler [req]
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

(defn list-youtube-channels-handler [req]
  (mail-only-guard req
    (fn [user-id]
      {:status 200 :body (mapv present-channel
                               (db.youtube/list-channels (common/ensure-ds) user-id))})))

(defn add-youtube-channel-handler [req]
  (mail-only-guard req
    (fn [user-id]
      (let [{:keys [channel_id name min_duration_minutes enabled]} (:body req)
            channel-id (some-> channel_id str str/trim)
            min-mins (when min_duration_minutes
                       (try (Integer/parseInt (str min_duration_minutes))
                            (catch Exception _ nil)))]
        (cond
          (str/blank? channel-id)
          {:status 400 :body {:error "channel_id is required"}}

          (and (some? min_duration_minutes) (nil? min-mins))
          {:status 400 :body {:error "min_duration_minutes must be an integer"}}

          :else
          (try
            (let [created (db.youtube/add-channel
                            (common/ensure-ds) user-id
                            {:channel-id channel-id
                             :name (when-not (str/blank? name) (str/trim name))
                             :min-duration-minutes min-mins
                             :enabled (bool->int (if (nil? enabled) true enabled))})]
              {:status 201 :body (present-channel created)})
            (catch Exception e
              (if (re-find #"UNIQUE" (or (.getMessage e) ""))
                {:status 409 :body {:error "Channel already added for this user"}}
                (throw e)))))))))

(defn update-youtube-channel-handler [req]
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

(defn delete-youtube-channel-handler [req]
  (mail-only-guard req
    (fn [user-id]
      (let [channel-row-id (Integer/parseInt (get-in req [:params :id]))
            result (db.youtube/delete-channel (common/ensure-ds) user-id channel-row-id)]
        (if (:success result)
          {:status 200 :body {:success true}}
          {:status 404 :body {:success false :error "Channel not found"}})))))
