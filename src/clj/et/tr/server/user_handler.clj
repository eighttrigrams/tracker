(ns et.tr.server.user-handler
  (:require [et.tr.server.common :as common]
            [et.tr.db.user :as db.user]
            [et.tr.auth :as auth]
            [clojure.string :as str]))

(defn login-handler [req]
  (let [{:keys [username password]} (:body req)]
    (if (common/allow-skip-logins?)
      (if (= username "admin")
        {:status 200 :body {:success true :user {:id nil :username "admin" :is_admin true :has_mail false :language "en"}}}
        (if-let [user (db.user/get-user-by-username (common/ensure-ds) username)]
          {:status 200 :body {:success true :user (-> user (dissoc :password_hash) (update :has_mail #(= 1 %)))}}
          {:status 401 :body {:success false :error "User not found"}}))
      (if (= username "admin")
        (if (= password (common/admin-password))
          {:status 200 :body {:success true
                              :token (auth/create-token nil "admin" true false)
                              :user {:id nil :username "admin" :is_admin true :has_mail false :language "en"}}}
          {:status 401 :body {:success false :error "Invalid credentials"}})
        (if-let [user (db.user/verify-user (common/ensure-ds) username password)]
          (let [has-mail (= 1 (:has_mail user))]
            {:status 200 :body {:success true
                                :token (auth/create-token (:id user) (:username user) false has-mail)
                                :user (assoc user :has_mail has-mail)}})
          {:status 401 :body {:success false :error "Invalid credentials"}})))))

(defn password-required-handler [_req]
  {:status 200 :body {:required (not (common/allow-skip-logins?))}})

(defn available-users-handler [_req]
  (if (common/allow-skip-logins?)
    (let [users (->> (db.user/list-users (common/ensure-ds))
                      (map #(update % :has_mail (fn [v] (= 1 v)))))
          admin {:id nil :username "admin" :is_admin true :has_mail false :language "en"}]
      {:status 200 :body (cons admin users)})
    {:status 403 :body {:error "Not available in production mode"}}))

(defn list-users-handler [req]
  (if (common/is-admin? req)
    {:status 200 :body (db.user/list-users (common/ensure-ds))}
    {:status 403 :body {:error "Admin access required"}}))

(defn add-user-handler [req]
  (if (common/is-admin? req)
    (let [{:keys [username password]} (:body req)]
      (if (or (str/blank? username) (str/blank? password))
        {:status 400 :body {:error "Username and password are required"}}
        (if (= username "admin")
          {:status 400 :body {:error "Cannot create user named 'admin'"}}
          (try
            (let [user (db.user/create-user (common/ensure-ds) username password)]
              {:status 201 :body (dissoc user :password_hash)})
            (catch Exception _
              {:status 409 :body {:error "Username already exists"}})))))
    {:status 403 :body {:error "Admin access required"}}))

(defn delete-user-handler [req]
  (if (common/is-admin? req)
    (let [user-id (Integer/parseInt (get-in req [:params :id]))
          result (db.user/delete-user (common/ensure-ds) user-id)]
      (if (:success result)
        {:status 200 :body {:success true}}
        {:status 404 :body {:error "User not found"}}))
    {:status 403 :body {:error "Admin access required"}}))

(def ^:private valid-languages #{"en" "de" "pt"})

(defn update-language-handler [req]
  (let [user-info (common/get-user-from-request req)
        user-id (:user-id user-info)
        {:keys [language]} (:body req)]
    (cond
      (:is-admin user-info)
      {:status 400 :body {:error "Admin language cannot be changed"}}

      (nil? user-id)
      {:status 400 :body {:error "User not found"}}

      (not (contains? valid-languages language))
      {:status 400 :body {:error "Invalid language"}}

      :else
      (if-let [result (db.user/set-user-language (common/ensure-ds) user-id language)]
        {:status 200 :body result}
        {:status 404 :body {:error "User not found"}}))))
