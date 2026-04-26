(ns et.tr.server.common
  (:require [et.tr.db :as db]
            [et.tr.auth :as auth]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
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
        (edn/read-string (slurp config-file)))
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

(defn fetch-youtube-title [url]
  (try
    (let [resp (http/get "https://www.youtube.com/oembed"
                 {:query-params {:url url :format "json"}
                  :as :json
                  :socket-timeout 5000
                  :connection-timeout 5000})
          {:keys [author_name title]} (:body resp)]
      (when title
        (if author_name
          (str author_name " — " title)
          title)))
    (catch Exception e
      (tel/log! {:level :warn :data {:url url :error (.getMessage e)}} "Failed to fetch YouTube title")
      nil)))

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

(defn make-categorize-handler [db-fn]
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
          {:status 200 :body {:success true}})))))

(defn make-uncategorize-handler [db-fn]
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
          {:status 200 :body {:success true}})))))

(defn make-entity-property-handler [field valid-values error-message {:keys [entity-type set-fn]}]
  (fn [req]
    (let [value (get-in req [:body field])]
      (if-not (contains? valid-values value)
        {:status 400 :body {:error error-message}}
        (let [user-id (get-user-id req)
              entity-id (Integer/parseInt (get-in req [:params :id]))
              result (set-fn (ensure-ds) user-id entity-id field value)]
          (if result
            {:status 200 :body result}
            {:status 404 :body {:error (str (name entity-type) " not found")}}))))))
