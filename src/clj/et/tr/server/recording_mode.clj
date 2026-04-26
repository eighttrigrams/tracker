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

(defn- machine-claims [req]
  (when-let [token (auth/extract-token req)]
    (when-let [claims (auth/verify-token token)]
      (when (:is-machine-user claims) claims))))

(defn wrap-machine-write-guard
  "When a verified token marks the caller as a machine user and the request
  mutates an /api/* endpoint, only let it through while recording mode is on.
  Otherwise log the intent and return a stub response — read access stays
  open regardless."
  [handler]
  (fn [req]
    (if (and (api-request? req) (mutating? req))
      (if-let [claims (machine-claims req)]
        (do (tel/log! {:level :info
                       :data {:intent :machine-write
                              :uri (:uri req)
                              :method (:request-method req)
                              :machine-user-id (:user-id claims)
                              :for-user-id (:for-user-id claims)
                              :recording (enabled?)}}
                      "MACHINE WRITE")
            (if (enabled?)
              (handler req)
              {:status 200
               :headers {"Content-Type" "application/json"}
               :body "{\"dropped\":true}"}))
        (handler req))
      (handler req))))
