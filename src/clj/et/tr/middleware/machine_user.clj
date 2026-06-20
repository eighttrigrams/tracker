(ns et.tr.middleware.machine-user
  (:require [et.tr.auth :as auth]))

(def ^:private capped-paths
  #{"/api/tasks" "/api/meets" "/api/messages" "/api/resources"
    "/api/journals" "/api/journal-entries"
    "/api/recurring-tasks" "/api/meeting-series"})

(defn- normalize-uri [uri]
  (if (and (> (count uri) 1) (.endsWith ^String uri "/"))
    (subs uri 0 (dec (count uri)))
    uri))

(defn- list-get? [req]
  (and (= :get (:request-method req))
       (contains? capped-paths (normalize-uri (or (:uri req) "")))))

(defn- machine? [req]
  (some-> (auth/extract-token req)
          auth/verify-token
          :is-machine-user
          boolean))

(defn wrap-machine-default-limit
  "When a verified machine-user calls GET on one of the unbounded list
  endpoints without an explicit ?limit, inject ?limit=100 into :params so
  downstream handlers receive a default cap. Caller-supplied ?limit values
  are preserved verbatim."
  [handler]
  (fn [req]
    (if (and (list-get? req)
             (nil? (get-in req [:params "limit"]))
             (machine? req))
      (handler (assoc-in req [:params "limit"] "100"))
      (handler req))))
