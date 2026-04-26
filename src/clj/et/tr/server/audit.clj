(ns et.tr.server.audit
  (:require [clojure.string :as str]
            [taoensso.telemere :as tel]
            [et.tr.auth :as auth]))

(defn- mutating? [req] (#{:post :put :delete} (:request-method req)))

(defn- api-request? [req]
  (str/starts-with? (or (:uri req) "") "/api/"))

(defn- caller-info [req]
  (let [token (auth/extract-token req)
        claims (when token (auth/verify-token token))
        x-user-id (get-in req [:headers "x-user-id"])]
    (cond
      claims (cond-> {:via :token
                      :user-id (:user-id claims)
                      :username (:username claims)}
               (:is-machine-user claims) (assoc :machine? true
                                                :for-user-id (:for-user-id claims)))
      x-user-id {:via :x-user-id :user-id x-user-id}
      :else    {:via :anonymous})))

(defn wrap-write-audit
  "Log every mutating /api/* request with the caller's identity. Wired
  outside the recording-mode gate so dropped machine writes still leave a
  trail (a dropped write produces both MACHINE WRITE and API WRITE entries;
  a regular write produces only API WRITE)."
  [handler]
  (fn [req]
    (when (and (api-request? req) (mutating? req))
      (tel/log! {:level :info
                 :data (assoc (caller-info req)
                              :uri (:uri req)
                              :method (:request-method req))}
                "API WRITE"))
    (handler req)))
