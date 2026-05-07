(ns et.tr.server.recording-mode
  (:require [clojure.string :as str]
            [taoensso.telemere :as tel]
            [et.tr.auth :as auth]))

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
            (if (and (enabled?) (not (:mail-only claims)))
              (handler req)
              {:status 200
               :headers {"Content-Type" "application/json"}
               :body "{\"dropped\":true}"}))
        (handler req))
      (handler req))))
