(ns et.tr.server.event-handler
  (:require [et.tr.server.common :as common]
            [et.tr.db.event :as db.event]))

(defn list-events-handler [req]
  (let [user-info (common/get-user-from-request req)
        user-id (:user-id user-info)
        limit (or (some-> (get-in req [:params "limit"]) Integer/parseInt) 100)
        capped (min (max limit 1) 100)]
    (cond
      (nil? user-info)
      {:status 401 :body {:error "Authentication required"}}

      (nil? user-id)
      {:status 200 :body {:events [] :limit capped}}

      :else
      {:status 200
       :body {:events (db.event/list-events-for-user (common/ensure-ds) user-id capped)
              :limit capped}})))
