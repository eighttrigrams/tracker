(ns et.tr.server.user-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.db.user :as db.user]
            [et.tr.auth :as auth]
            [clojure.string :as str]))

(defn login-handler
  "POST /api/auth/login — public (unauthenticated) login endpoint. Body
  fields: :username and :password. When skip-logins is enabled the password
  is ignored and a bare user record is returned (admin gets a synthetic
  one). Otherwise the password is verified; admins use common/admin-password
  and other users go through db.user/verify-user. Machine users have their
  effective :has_mail and :mail_only resolved from the target user. On
  success returns 200 {:success true :token :user}; on failure 401
  {:success false :error \"Invalid credentials\"} or \"User not found\"."
  [req]
  (let [{:keys [username password]} (:body req)]
    (if (common/allow-skip-logins?)
      (if (= username "admin")
        {:status 200 :body {:success true :user {:id nil :username "admin" :is_admin true :has_mail false :language "en" :vim_keys 0}}}
        (if-let [user (db.user/get-user-by-username (common/ensure-ds) username)]
          {:status 200 :body {:success true :user (-> user (dissoc :password_hash) (update :has_mail #(= 1 %)))}}
          {:status 401 :body {:success false :error "User not found"}}))
      (if (= username "admin")
        (if (= password (common/admin-password))
          {:status 200 :body {:success true
                              :token (auth/create-token nil "admin" true false)
                              :user {:id nil :username "admin" :is_admin true :has_mail false :language "en" :vim_keys 0}}}
          {:status 401 :body {:success false :error "Invalid credentials"}})
        (if-let [user (db.user/verify-user (common/ensure-ds) username password)]
          (let [machine? (= 1 (:is_machine_user user))
                target (when machine?
                         (db.user/get-user-by-id (common/ensure-ds) (:for_user_id user)))
                effective-has-mail (if machine?
                                     (= 1 (:has_mail target))
                                     (= 1 (:has_mail user)))
                mail-only? (and machine? (= 1 (:mail_only user)))
                claims (cond-> {:user-id (:id user) :username (:username user)
                                :is-admin false :has-mail effective-has-mail}
                         machine? (assoc :is-machine-user true
                                         :for-user-id (:for_user_id user)
                                         :mail-only mail-only?))]
            {:status 200 :body {:success true
                                :token (auth/create-token claims)
                                :user (-> user
                                          (assoc :has_mail effective-has-mail)
                                          (update :is_machine_user #(= 1 %))
                                          (update :mail_only #(= 1 %)))}})
          {:status 401 :body {:success false :error "Invalid credentials"}})))))

(defn password-required-handler
  "GET /api/auth/required — public probe used by the login UI to decide
  whether to show a password field. Returns 200 {:required boolean} where
  :required is true unless common/allow-skip-logins? is on (dev/single-user
  mode)."
  [_req]
  {:status 200 :body {:required (not (common/allow-skip-logins?))}})

(defn available-users-handler
  "GET /api/auth/available-users — list users selectable from the dev login
  picker. Only enabled when common/allow-skip-logins? is true; otherwise
  returns 403 {:error \"Not available in production mode\"}. Filters out
  machine users and prepends the synthetic admin entry. Returns 200 with a
  seq of {:id :username :is_admin :has_mail :language :vim_keys} maps."
  [_req]
  (if (common/allow-skip-logins?)
    (let [users (->> (db.user/list-users (common/ensure-ds))
                     (remove #(= 1 (:is_machine_user %)))
                     (map #(update % :has_mail (fn [v] (= 1 v)))))
          admin {:id nil :username "admin" :is_admin true :has_mail false :language "en" :vim_keys 0}]
      {:status 200 :body (cons admin users)})
    {:status 403 :body {:error "Not available in production mode"}}))

(defn list-users-handler
  "GET /api/users — admin-only listing of all users. Returns 200 with a
  vector of user rows (with :is_machine_user and :mail_only coerced to
  booleans) when common/is-admin? is satisfied, otherwise 403 {:error
  \"Admin access required\"}."
  [req]
  (if (common/is-admin? req)
    {:status 200 :body (->> (db.user/list-users (common/ensure-ds))
                            (mapv #(-> %
                                       (update :is_machine_user (fn [v] (= 1 v)))
                                       (update :mail_only (fn [v] (= 1 v))))))}
    {:status 403 :body {:error "Admin access required"}}))

(defn add-user-handler
  "POST /api/users — admin-only user creation. Body fields: :username and
  :password (both required, non-blank), :is_machine_user (boolean),
  :for_user_id (required when is_machine_user, must reference a real
  non-machine user), :mail_only (boolean, machine users only). Returns 201
  with the created user (sans :password_hash), 400 {:error} on validation
  failure, 409 {:error} on duplicate username, or 403 when not admin.
  Records a system :user-create event."
  [req]
  (if (common/is-admin? req)
    (let [{:keys [username password is_machine_user for_user_id mail_only]} (:body req)
          machine? (boolean is_machine_user)
          mail-only? (boolean mail_only)]
      (cond
        (or (str/blank? username) (str/blank? password))
        {:status 400 :body {:error "Username and password are required"}}

        (= username "admin")
        {:status 400 :body {:error "Cannot create user named 'admin'"}}

        (and machine? (nil? for_user_id))
        {:status 400 :body {:error "Machine user requires a target user"}}

        (and machine?
             (let [target (db.user/get-user-by-id (common/ensure-ds) for_user_id)]
               (or (nil? target) (= 1 (:is_machine_user target)))))
        {:status 400 :body {:error "Target user is invalid"}}

        :else
        (try
          (let [user (db.user/create-user (common/ensure-ds) username password
                                          {:is-machine-user machine?
                                           :for-user-id (when machine? for_user_id)
                                           :mail-only (and machine? mail-only?)})]
            (events/record! req {:entity-type :user
                                 :entity-id (:id user)
                                 :action :user-create
                                 :system? true
                                 :payload {:username username
                                           :is_machine machine?
                                           :for_user_id (when machine? for_user_id)
                                           :mail_only (and machine? mail-only?)}})
            {:status 201 :body (-> user
                                   (dissoc :password_hash)
                                   (update :is_machine_user #(= 1 %))
                                   (update :mail_only #(= 1 %)))})
          (catch Exception _
            {:status 409 :body {:error "Username already exists"}}))))
    {:status 403 :body {:error "Admin access required"}}))

(defn delete-user-handler
  "DELETE /api/users/:id — admin-only user deletion. Path param :id is
  parsed as an integer. Returns 200 {:success true} on success, 404
  {:error \"User not found\"} when the row does not exist, or 403 {:error
  \"Admin access required\"} when the caller is not an admin. Records a
  system :user-delete event with the deleted user's identifying fields."
  [req]
  (if (common/is-admin? req)
    (let [user-id (Integer/parseInt (get-in req [:params :id]))
          target (db.user/get-user-by-id (common/ensure-ds) user-id)
          result (db.user/delete-user (common/ensure-ds) user-id)]
      (if (:success result)
        (do (events/record! req {:entity-type :user
                                 :entity-id user-id
                                 :action :user-delete
                                 :system? true
                                 :payload {:username (:username target)
                                           :is_machine (= 1 (:is_machine_user target))
                                           :for_user_id (:for_user_id target)
                                           :mail_only (= 1 (:mail_only target))}})
            {:status 200 :body {:success true}})
        {:status 404 :body {:error "User not found"}}))
    {:status 403 :body {:error "Admin access required"}}))

(def ^:private valid-languages #{"en" "de" "pt"})

(defn update-language-handler
  "PUT /api/user/language — set the calling user's UI :language. Body
  field :language must be one of #{\"en\" \"de\" \"pt\"}. Admin callers are
  rejected with 400 since the synthetic admin has no row. Returns 200 with
  the updated row, 400 {:error} on invalid input or admin caller, or 404
  {:error} when the user row cannot be located."
  [req]
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

(defn update-vim-keys-handler
  "PUT /api/user/vim-keys — toggle the caller's vim-keys preference. Body
  field :vim_keys must be 0 or 1 (stored as boolean in the DB). Admin
  callers are rejected with 400 since the synthetic admin has no row.
  Returns 200 with the updated row, 400 {:error} on invalid input or admin
  caller, or 404 {:error} when the user row cannot be located."
  [req]
  (let [user-info (common/get-user-from-request req)
        user-id (:user-id user-info)
        vim-keys (:vim_keys (:body req))]
    (cond
      (:is-admin user-info)
      {:status 400 :body {:error "Admin settings cannot be changed"}}

      (nil? user-id)
      {:status 400 :body {:error "User not found"}}

      (not (contains? #{0 1} vim-keys))
      {:status 400 :body {:error "Invalid value"}}

      :else
      (if-let [result (db.user/set-vim-keys (common/ensure-ds) user-id (= 1 vim-keys))]
        {:status 200 :body result}
        {:status 404 :body {:error "User not found"}}))))

(defn- human-caller
  "Return the caller's user-info map only if the request is from a
  logged-in non-admin human. Machine-user tokens, the synthetic admin,
  and unauthenticated callers all yield nil. Used to gate the
  /api/me/machine-users endpoints — see point 4 of the design: only
  human owners may manage their machine users."
  [req]
  (let [u (common/get-user-from-request req)]
    (when (and u
               (:user-id u)
               (not (:is-admin u))
               (not (:machine? u)))
      u)))

(defn- present-machine-user [row]
  (when row
    (-> row
        (update :is_machine_user #(= 1 %))
        (update :mail_only #(= 1 %)))))

(defn- owned-machine-user
  "Load the target user row and return it only if it is a machine user
  whose for_user_id matches `parent-id`. Returns nil otherwise so callers
  can respond with a uniform 404 (no existence leak)."
  [ds parent-id target-id]
  (let [target (db.user/get-user-by-id ds target-id)]
    (when (and target
               (= 1 (:is_machine_user target))
               (= parent-id (:for_user_id target)))
      target)))

(defn list-my-machine-users-handler
  "GET /api/me/machine-users — list the caller's machine users. Returns
  403 for admin/machine-user/unauthenticated callers, 200 with a vector
  of {:id :username :for_user_id :mail_only :created_at} otherwise."
  [req]
  (if-let [{:keys [user-id]} (human-caller req)]
    {:status 200 :body (->> (db.user/list-machine-users-for-user
                              (common/ensure-ds) user-id)
                            (mapv #(update % :mail_only (fn [v] (= 1 v)))))}
    {:status 403 :body {:error "Forbidden"}}))

(defn create-my-machine-user-handler
  "POST /api/me/machine-users — create a machine user owned by the
  caller. Body: :username (required), :password (required), :mail_only
  (boolean, default false). The caller's id is forced into for_user_id;
  any client-supplied for_user_id / is_machine_user is ignored. Returns
  201 with the row, 400 on validation failure, 409 on duplicate
  username, 403 for non-human callers."
  [req]
  (if-let [{:keys [user-id]} (human-caller req)]
    (let [{:keys [username password mail_only]} (:body req)]
      (cond
        (or (str/blank? username) (str/blank? password))
        {:status 400 :body {:error "Username and password are required"}}

        (= username "admin")
        {:status 400 :body {:error "Cannot create user named 'admin'"}}

        :else
        (try
          (let [user (db.user/create-user (common/ensure-ds) username password
                                          {:is-machine-user true
                                           :for-user-id user-id
                                           :mail-only (boolean mail_only)})]
            (events/record! req {:entity-type :user
                                 :entity-id (:id user)
                                 :action :user-create
                                 :payload {:username username
                                           :is_machine true
                                           :for_user_id user-id
                                           :mail_only (boolean mail_only)}})
            {:status 201 :body (-> user
                                   (dissoc :password_hash)
                                   present-machine-user)})
          (catch Exception _
            {:status 409 :body {:error "Username already exists"}}))))
    {:status 403 :body {:error "Forbidden"}}))

(defn update-my-machine-user-handler
  "PUT /api/me/machine-users/:id — rename or toggle mail_only on a
  machine user owned by the caller. Body fields are optional; omitted
  fields stay unchanged. Body keys: :username, :mail_only. Returns 200
  with the updated row, 400 on empty body / blank rename, 404 when the
  target is not a machine user owned by the caller, 409 on duplicate
  username, 403 for non-human callers."
  [req]
  (if-let [{:keys [user-id]} (human-caller req)]
    (let [target-id (Integer/parseInt (get-in req [:params :id]))
          ds (common/ensure-ds)
          {:keys [username mail_only] :as body} (:body req)
          rename? (contains? body :username)
          flip-mail? (contains? body :mail_only)]
      (cond
        (nil? (owned-machine-user ds user-id target-id))
        {:status 404 :body {:error "Machine user not found"}}

        (not (or rename? flip-mail?))
        {:status 400 :body {:error "Nothing to update"}}

        (and rename? (str/blank? username))
        {:status 400 :body {:error "Username cannot be blank"}}

        (and rename? (= username "admin"))
        {:status 400 :body {:error "Cannot rename to 'admin'"}}

        :else
        (try
          (let [_ (when rename?
                    (db.user/update-username ds target-id username))
                _ (when flip-mail?
                    (db.user/update-mail-only ds target-id (boolean mail_only)))
                fresh (db.user/get-user-by-id ds target-id)]
            (events/record! req {:entity-type :user
                                 :entity-id target-id
                                 :action :user-update
                                 :payload (cond-> {}
                                            rename?    (assoc :username username)
                                            flip-mail? (assoc :mail_only (boolean mail_only)))})
            {:status 200 :body (present-machine-user fresh)})
          (catch Exception _
            {:status 409 :body {:error "Username already exists"}}))))
    {:status 403 :body {:error "Forbidden"}}))

(defn update-my-machine-user-password-handler
  "PUT /api/me/machine-users/:id/password — rotate the password on a
  caller-owned machine user. Body: :password (required, non-blank).
  Returns 200 {:success true}, 400 on blank password, 404 when target
  not owned, 403 for non-human callers."
  [req]
  (if-let [{:keys [user-id]} (human-caller req)]
    (let [target-id (Integer/parseInt (get-in req [:params :id]))
          ds (common/ensure-ds)
          {:keys [password]} (:body req)]
      (cond
        (nil? (owned-machine-user ds user-id target-id))
        {:status 404 :body {:error "Machine user not found"}}

        (str/blank? password)
        {:status 400 :body {:error "Password is required"}}

        :else
        (do (db.user/update-password ds target-id password)
            (events/record! req {:entity-type :user
                                 :entity-id target-id
                                 :action :user-password-reset
                                 :payload {}})
            {:status 200 :body {:success true}})))
    {:status 403 :body {:error "Forbidden"}}))

(defn delete-my-machine-user-handler
  "DELETE /api/me/machine-users/:id — remove a caller-owned machine
  user. Returns 200 {:success true}, 404 when target not owned, 403
  for non-human callers. Records a :user-delete event."
  [req]
  (if-let [{:keys [user-id]} (human-caller req)]
    (let [target-id (Integer/parseInt (get-in req [:params :id]))
          ds (common/ensure-ds)
          target (owned-machine-user ds user-id target-id)]
      (if (nil? target)
        {:status 404 :body {:error "Machine user not found"}}
        (let [result (db.user/delete-user ds target-id)]
          (if (:success result)
            (do (events/record! req {:entity-type :user
                                     :entity-id target-id
                                     :action :user-delete
                                     :payload {:username (:username target)
                                               :is_machine true
                                               :for_user_id user-id
                                               :mail_only (= 1 (:mail_only target))}})
                {:status 200 :body {:success true}})
            {:status 404 :body {:error "Machine user not found"}}))))
    {:status 403 :body {:error "Forbidden"}}))
