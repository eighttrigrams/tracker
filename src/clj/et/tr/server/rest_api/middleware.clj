(ns et.tr.server.rest-api.middleware
  (:require [clojure.string :as str]
            [taoensso.telemere :as tel]
            [et.tr.auth :as auth]
            [et.tr.server.common :as common]
            [et.tr.server.rest-api.util :refer [json-response]]))

(defonce ^:private *recording? (atom false))

(defn enabled? [] @*recording?)

(defn toggle! [] (swap! *recording? not))

(defn log-and-guard
  "Log the intended write action, then either run `thunk` (when recording)
   or drop the request silently and return `dropped-response` instead.
   The log line does not reveal whether the request actually executed —
   it records the intent identically either way."
  [intent details dropped-response thunk]
  (tel/log! {:level :info :data (assoc details :intent intent)} (str "REST " intent))
  (if (enabled?) (thunk) dropped-response))

(defn wrap-rest-auth
  "Auth gate for /rest/*.
   - Production: every endpoint except /rest/auth/login requires a valid Bearer
     token. Claims from the token are attached as :rest-user.
   - Dev (non-prod): no token required — uses the shared get-user-from-request
     helper (X-User-Id header or the first user) so local LLM agents can hit
     the API without logging in. Writes are still gated by recording mode."
  [handler]
  (fn [req]
    (let [uri (or (:uri req) "")]
      (cond
        (not (str/starts-with? uri "/rest/")) (handler req)
        (= uri "/rest/auth/login") (handler req)
        (common/prod-mode?)
          (if-let [token (auth/extract-token req)]
            (if-let [claims (auth/verify-token token)]
              (handler (assoc req :rest-user claims))
              (json-response 401 {:error "Invalid token"}))
            (json-response 401 {:error "Authentication required"}))
        :else (handler (assoc req :rest-user (common/get-user-from-request req)))))))
