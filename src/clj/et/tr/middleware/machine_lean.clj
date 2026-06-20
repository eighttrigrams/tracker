(ns et.tr.middleware.machine-lean
  (:require [et.tr.auth :as auth]
            [ring.util.codec :as codec]))

(defn- machine? [req]
  (some-> (auth/extract-token req)
          auth/verify-token
          :is-machine-user
          boolean))

(defn- detail-full? [req]
  (let [decoded (codec/form-decode (or (:query-string req) ""))]
    (and (map? decoded) (= "full" (get decoded "detail")))))

(defn- strip-item [item]
  (if (map? item)
    (dissoc item :description :tags)
    item))

(defn wrap-machine-lean
  "For a verified machine-user caller, when the request has no ?detail=full and
  the response :body is a sequential collection of maps (a list endpoint),
  dissoc :description and :tags from each item to keep list rows lean. Map-bodied
  responses (today-board, single-item GETs) and non-machine callers pass through
  untouched. Runs on the Clojure body before wrap-json-response serialises it."
  [handler]
  (fn [req]
    (let [resp (handler req)
          body (:body resp)]
      (if (and (machine? req)
               (not (detail-full? req))
               (sequential? body)
               (every? map? body))
        (assoc resp :body (mapv strip-item body))
        resp))))
