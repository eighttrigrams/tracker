(ns et.tr.server.common
  (:require [et.tr.db :as db]
            [et.tr.db.event :as db.event]
            [et.tr.auth :as auth]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [aero.core :as aero]
            [clj-http.client :as http]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]))

(defonce ds (atom nil))
(defonce *config (atom nil))

(defn load-config []
  (let [config-file (io/file "config.edn")]
    (if (.exists config-file)
      (do
        (tel/log! :info "Loading configuration from config.edn")
        (aero/read-config config-file))
      (do
        (tel/log! :info "config.edn not found, using defaults")
        {}))))

(defn ensure-ds []
  (when (nil? @ds)
    (when (nil? @*config)
      (reset! *config (load-config)))
    (let [conn (db/init-conn (get @*config :db {:type :sqlite-memory}))]
      (reset! ds conn)))
  @ds)

(defn prod-mode? []
  (let [on-fly? (some? (System/getenv "FLY_APP_NAME"))
        dev-mode? (= "true" (System/getenv "DEV"))
        admin-pw (System/getenv "ADMIN_PASSWORD")]
    (cond
      (or on-fly? (not dev-mode?))
      (do (when-not admin-pw
            (throw (ex-info "ADMIN_PASSWORD required in production" {})))
          true)
      admin-pw
      true
      :else
      false)))

(defn allow-skip-logins? []
  (and (true? (:dangerously-skip-logins? @*config))
       (not (prod-mode?))))

(defn- claims->identity [claims]
  (when claims
    (if (:is-machine-user claims)
      (assoc claims
             :machine? true
             :machine-user-id (:user-id claims)
             :user-id (:for-user-id claims))
      claims)))

(defn- ds-username [user-id]
  (when user-id
    (-> (jdbc/execute-one! (db/get-conn (ensure-ds))
          (sql/format {:select [:username] :from [:users] :where [:= :id user-id]})
          db/jdbc-opts)
        :username)))

(defn claims->actor
  "Build the actor map that downstream DB writes use to record events.
  Distinguishes the raw caller (machine row when applicable) from the
  parent (target) user, and snapshots usernames so we can still display
  history after the user is deleted."
  [claims]
  (when claims
    (let [machine? (boolean (:is-machine-user claims))]
      (cond-> {:actor-user-id (:user-id claims)
               :actor-username (or (:username claims)
                                   (ds-username (:user-id claims))
                                   "unknown")
               :is-machine? machine?}
        machine? (assoc :parent-user-id (:for-user-id claims)
                        :parent-username (ds-username (:for-user-id claims)))))))

(defn get-actor [req]
  (or (some-> (auth/extract-token req) auth/verify-token claims->actor)
      (when (allow-skip-logins?)
        (let [user-id-str (get-in req [:headers "x-user-id"])
              user-id (when (and user-id-str (not= user-id-str "null"))
                        (Integer/parseInt user-id-str))]
          (if user-id
            {:actor-user-id user-id
             :actor-username (or (ds-username user-id) "unknown")
             :is-machine? false}
            {:actor-user-id nil
             :actor-username "admin"
             :is-machine? false})))))

(defn get-user-from-request [req]
  (or (some-> (auth/extract-token req) auth/verify-token claims->identity)
      (when (allow-skip-logins?)
        (let [user-id-str (get-in req [:headers "x-user-id"])]
          (if (or (nil? user-id-str) (= user-id-str "null"))
            (let [first-user (jdbc/execute-one! (db/get-conn (ensure-ds))
                               (sql/format {:select [:id :has_mail] :from [:users] :order-by [[:id :asc]] :limit 1})
                               db/jdbc-opts)]
              {:user-id (:id first-user) :is-admin true :has-mail (= 1 (:has_mail first-user))})
            (let [user-id (Integer/parseInt user-id-str)
                  user (jdbc/execute-one! (db/get-conn (ensure-ds))
                         (sql/format {:select [:has_mail] :from [:users] :where [:= :id user-id]})
                         db/jdbc-opts)]
              {:user-id user-id :is-admin false :has-mail (= 1 (:has_mail user))}))))))

(defn get-user-id [req]
  (:user-id (get-user-from-request req)))

(defn admin-password []
  (or (System/getenv "ADMIN_PASSWORD")
      (when (= "true" (System/getenv "DEV")) "admin")
      (throw (ex-info "ADMIN_PASSWORD env var is required" {}))))

(defn is-admin? [req]
  (:is-admin (get-user-from-request req)))

(defn has-mail? [req]
  (:has-mail (get-user-from-request req)))

(defn parse-category-param [param]
  (when (and param (not (str/blank? param)))
    (vec (str/split param #","))))

(defn parse-int-opt [s]
  (when (and s (not (str/blank? s)))
    (try (Integer/parseInt (str/trim s))
         (catch NumberFormatException _ nil))))

(defn valid-time-format? [time-str]
  (or (nil? time-str)
      (empty? time-str)
      (re-matches #"^([01]\d|2[0-3]):([0-5]\d)$" time-str)))

(defn valid-date-format? [date-str]
  (or (nil? date-str)
      (empty? date-str)
      (re-matches #"^\d{4}-\d{2}-\d{2}$" date-str)))

(defn valid-url? [link]
  (some? (re-matches #"https?://\S+" link)))

(defn youtube-url? [url]
  (some? (re-find #"(?:youtube\.com/watch|youtube\.com/shorts/|youtu\.be/)" url)))

(defn fetch-youtube-oembed
  "Calls YouTube's oEmbed endpoint. Returns {:author … :title …} or nil."
  [url]
  (try
    (let [resp (http/get "https://www.youtube.com/oembed"
                 {:query-params {:url url :format "json"}
                  :as :json
                  :socket-timeout 5000
                  :connection-timeout 5000})
          {:keys [author_name title]} (:body resp)]
      (when (or author_name title)
        {:author author_name :title title}))
    (catch Exception e
      (tel/log! {:level :warn :data {:url url :error (.getMessage e)}} "Failed to fetch YouTube oEmbed")
      nil)))

(defn fetch-youtube-title [url]
  (when-let [{:keys [author title]} (fetch-youtube-oembed url)]
    (when title
      (if author
        (str author " — " title)
        title))))

(defn extract-youtube-url
  "Find the first YouTube URL in `text`, or nil."
  [text]
  (when (string? text)
    (some (fn [u] (when (youtube-url? u) u))
          (re-seq #"https?://\S+" text))))

(def ^:private dropped-title-re #"(?i)^New YouTube video.*just dropped\.?\s*$")

(defn youtube-message-not-yet-titled?
  "True if a message looks like a freshly-arrived YouTube inbox item whose
  title hasn't been replaced with the actual video title yet."
  [{:keys [sender title]}]
  (and (= sender "YouTube")
       (string? title)
       (boolean (re-matches dropped-title-re title))))

(defn build-dropped-title [author]
  (if (and author (not (str/blank? author)))
    (str "New YouTube video from \"" author "\" just dropped.")
    "New YouTube video just dropped."))

(def ^:private atom-placeholder-title-re #"(?i)^New post from \".+\"\s*$")

(defn atom-message-not-yet-titled?
  "True for an atom-feed inbox message whose title is still the generic
  'New post from \"X\"' placeholder set by the source worker."
  [{:keys [title]}]
  (and (string? title)
       (boolean (re-matches atom-placeholder-title-re title))))

(defn extract-atom-entry-title
  "Pull the entry title out of an atom inbox message body. The source
  worker parks it as the first <h2><a>TITLE</a></h2> (html payload) or
  `# TITLE` line (markdown payload). Returns nil when neither is
  present."
  [body]
  (when (string? body)
    (let [t (or (some-> (re-find #"(?is)<h2>\s*<a[^>]*>(.+?)</a>\s*</h2>" body)
                        second)
                (some-> (re-find #"(?is)<h2>(.+?)</h2>" body)
                        second)
                (some-> (re-find #"(?m)^#\s+(.+)$" body)
                        second))]
      (when t
        (let [trimmed (str/trim t)]
          (when-not (str/blank? trimmed) trimmed))))))

(defn substack-url? [url]
  (some? (re-find #"\.substack\.com/" url)))

(defn fetch-substack-title [url]
  (try
    (let [resp (http/get url
                 {:as :string
                  :socket-timeout 5000
                  :connection-timeout 5000})
          body (:body resp)
          title (second (re-find #"<title[^>]*>([^<]+)</title>" body))]
      (when title
        (str/trim title)))
    (catch Exception e
      (tel/log! {:level :warn :data {:url url :error (.getMessage e)}} "Failed to fetch Substack title")
      nil)))

(declare get-actor)

(defn- record-link-event!
  "Recorded only after a successful db write. Looks up the category title
  via a fresh select."
  [req entity-type entity-id action category-type category-id]
  (try
    (let [tbl ({"person" :people "place" :places
                "project" :projects "goal" :goals} category-type)
          title (when tbl
                  (:name (jdbc/execute-one! (db/get-conn (ensure-ds))
                           (sql/format {:select [:name] :from [tbl]
                                        :where [:= :id category-id]})
                           db/jdbc-opts)))]
      (when-let [actor (get-actor req)]
        (db.event/record-event!
         (ensure-ds) actor
         {:entity-type (name entity-type)
          :entity-id entity-id
          :action action
          :payload {:category-type category-type
                    :category-id category-id
                    :category-title title}})))
    (catch Throwable _ nil)))

(defn make-categorize-handler
  ([db-fn] (make-categorize-handler db-fn nil))
  ([db-fn entity-type]
   (fn [req]
     (let [user-id (get-user-id req)
           entity-id (Integer/parseInt (get-in req [:params :id]))
           {:keys [category-type category-id]} (:body req)]
       (cond
         (or (nil? category-type) (str/blank? category-type))
         {:status 400 :body {:success false :error "category-type is required"}}

         (or (nil? category-id) (not (integer? category-id)) (< category-id 1))
         {:status 400 :body {:success false :error "category-id must be a positive integer"}}

         :else
         (do
           (db-fn (ensure-ds) user-id entity-id category-type category-id)
           (when entity-type
             (record-link-event! req entity-type entity-id :link category-type category-id))
           {:status 200 :body {:success true}}))))))

(defn make-uncategorize-handler
  ([db-fn] (make-uncategorize-handler db-fn nil))
  ([db-fn entity-type]
   (fn [req]
     (let [user-id (get-user-id req)
           entity-id (Integer/parseInt (get-in req [:params :id]))
           {:keys [category-type category-id]} (:body req)]
       (cond
         (or (nil? category-type) (str/blank? category-type))
         {:status 400 :body {:success false :error "category-type is required"}}

         (or (nil? category-id) (not (integer? category-id)) (< category-id 1))
         {:status 400 :body {:success false :error "category-id must be a positive integer"}}

         :else
         (do
           (db-fn (ensure-ds) user-id entity-id category-type category-id)
           (when entity-type
             (record-link-event! req entity-type entity-id :unlink category-type category-id))
           {:status 200 :body {:success true}}))))))

(defn- record-property-event!
  "Emit an :update event with single-field shape after the db write
  succeeded. Best-effort — never throws."
  [req entity-type entity-id field old-value new-value]
  (try
    (when (not= old-value new-value)
      (when-let [actor (get-actor req)]
        (db.event/record-event!
         (ensure-ds) actor
         {:entity-type (name entity-type)
          :entity-id entity-id
          :action :update
          :payload {:field (name field)
                    :old-value old-value
                    :new-value new-value}})))
    (catch Throwable _ nil)))

(defn make-entity-property-handler [field valid-values error-message
                                    {:keys [entity-type set-fn table]}]
  (fn [req]
    (let [value (get-in req [:body field])]
      (if-not (contains? valid-values value)
        {:status 400 :body {:error error-message}}
        (let [user-id (get-user-id req)
              entity-id (Integer/parseInt (get-in req [:params :id]))
              old-row (when table
                        (jdbc/execute-one! (db/get-conn (ensure-ds))
                          (sql/format {:select [field] :from [(keyword table)]
                                       :where [:= :id entity-id]})
                          db/jdbc-opts))
              result (set-fn (ensure-ds) user-id entity-id field value)]
          (if result
            (do (when (and table entity-type)
                  (record-property-event! req entity-type entity-id
                                          field (get old-row field) (get result field)))
                {:status 200 :body result})
            {:status 404 :body {:error (str (name entity-type) " not found")}}))))))
