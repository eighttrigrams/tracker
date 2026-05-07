(ns et.tr.server.recording-mode
  (:require [clojure.string :as str]
            [taoensso.telemere :as tel]
            [et.tr.auth :as auth]
            [et.tr.db.event :as db.event]
            [et.tr.db.user :as db.user]
            [et.tr.server.common :as common]))

(defonce ^:private *recording? (atom false))

(defn enabled? [] @*recording?)

(defn toggle! [] (swap! *recording? not))

(defn- mutating? [req] (#{:post :put :delete} (:request-method req)))

(defn- api-request? [req]
  (str/starts-with? (or (:uri req) "") "/api/"))

(defn- gate-exempt?
  "Endpoints that machine users may always write to, regardless of recording
  mode. Mail ingestion lives here so the inbox keeps filling even when the
  human hasn't enabled recording — those messages still need triage in the UI
  before they affect anything."
  [req]
  (let [uri (or (:uri req) "")]
    (and (= :post (:request-method req))
         (or (= uri "/api/messages")
             (= uri "/api/messages/")))))

(defn- machine-claims [req]
  (when-let [token (auth/extract-token req)]
    (when-let [claims (auth/verify-token token)]
      (when (:is-machine-user claims) claims))))

(defn- read-body-string
  "Best-effort read of the request body as a string. Returns nil if the
  body has already been consumed or is non-readable. The middleware runs
  before wrap-json-body, so the body is still a stream here; we slurp it
  for the audit trail (and discard since we are about to short-circuit
  the request anyway)."
  [req]
  (try
    (when-let [b (:body req)]
      (cond
        (string? b) b
        (instance? java.io.InputStream b) (slurp b)
        :else (str b)))
    (catch Throwable _ nil)))

(defn- record-dropped-event! [req claims reason]
  (try
    (let [ds (common/ensure-ds)
          parent-id (:for-user-id claims)
          parent-username (when parent-id
                            (:username (db.user/get-user-by-id ds parent-id)))]
      (db.event/record-event!
       ds
       {:actor-user-id (:user-id claims)
        :actor-username (or (:username claims) "machine")
        :is-machine? true
        :parent-user-id parent-id
        :parent-username parent-username}
       {:entity-type :dropped
        :entity-id nil
        :action :dropped-write
        :dropped true
        :payload {:method (some-> (:request-method req) name)
                  :uri (:uri req)
                  :body (read-body-string req)
                  :reason (name reason)}}))
    (catch Throwable _ nil)))

(defn wrap-machine-write-guard
  "When a verified token marks the caller as a machine user and the request
  mutates an /api/* endpoint, only let it through while recording mode is on.
  Otherwise log the intent and return a stub response — read access stays
  open regardless. Mail-only machine users never get through to non-mail
  endpoints, even when recording is on."
  [handler]
  (fn [req]
    (if (and (api-request? req) (mutating? req) (not (gate-exempt? req)))
      (if-let [claims (machine-claims req)]
        (do (tel/log! {:level :info
                       :data {:intent :machine-write
                              :uri (:uri req)
                              :method (:request-method req)
                              :machine-user-id (:user-id claims)
                              :for-user-id (:for-user-id claims)
                              :mail-only (boolean (:mail-only claims))
                              :recording (enabled?)}}
                      "MACHINE WRITE")
            (cond
              (and (enabled?) (not (:mail-only claims)))
              (handler req)

              :else
              (do (record-dropped-event! req claims
                                         (if (:mail-only claims) :mail-only :recording-off))
                  {:status 200
                   :headers {"Content-Type" "application/json"}
                   :body "{\"dropped\":true}"})))
        (handler req))
      (handler req))))
