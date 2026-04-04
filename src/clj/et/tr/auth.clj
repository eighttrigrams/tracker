(ns et.tr.auth
  (:require [buddy.sign.jwt :as jwt]
            [clojure.string :as str]))

(defn jwt-secret []
  (or (System/getenv "ADMIN_PASSWORD")
      (when (= "true" (System/getenv "DEV")) "dev-secret")
      (throw (ex-info "ADMIN_PASSWORD env var is required" {}))))

(defn create-token [user-id username is-admin has-mail]
  (jwt/sign {:user-id user-id :username username :is-admin is-admin :has-mail has-mail} (jwt-secret)))

(defn verify-token [token]
  (try
    (jwt/unsign token (jwt-secret))
    (catch Exception _ nil)))

(defn extract-token [req]
  (when-let [auth-header (get-in req [:headers "authorization"])]
    (when (str/starts-with? auth-header "Bearer ")
      (subs auth-header 7))))
