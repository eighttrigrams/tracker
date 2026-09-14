(ns et.tr.ui.state.auth
  (:require [et.tr.ui.api :as api]
            [et.tr.ui.key-store :as key-store]
            [et.tr.ui.session :as session]
            [et.tr.i18n :as i18n]))

(defn save-auth-to-storage [token user]
  (when token
    (.setItem js/localStorage "auth-token" token))
  (when user
    (.setItem js/localStorage "auth-user" (js/JSON.stringify (clj->js user)))))

(defn clear-auth-from-storage []
  (.removeItem js/localStorage "auth-token")
  (.removeItem js/localStorage "auth-user"))

(defn load-auth-from-storage []
  (let [token (.getItem js/localStorage "auth-token")
        user-str (.getItem js/localStorage "auth-user")]
    {:token token
     :user (when user-str (js->clj (js/JSON.parse user-str) :keywordize-keys true))}))

(defn apply-user-language [user]
  (let [lang (or (:language user) "en")]
    (i18n/set-language! lang)))

(defn refresh-current-user
  "Re-fetch the current user from the DB and overwrite the cached copy, so
  DB-sourced settings (language, vim-keys, screensaver) win over whatever
  was frozen in localStorage. Updates the storage blob to match.

  **`seal_prose` arrives only here**, and that is why this now runs on every path
  that sets a current user rather than only on the one that restores a token.
  Neither the dev login picker nor the user switcher carries the flag — they
  build a user out of `/api/auth/available-users`, which deliberately answers a
  list of names and roles — so a client that gated the key panel on it and never
  asked `/api/auth/me` would offer the panel to nobody at all. The flag has one
  source because the server resolving it through `envelope/seals?` is what stops
  the client's gate and the server's refusal drifting apart.

  The answer is dropped if the user changed while it was in flight — see
  `et.tr.ui.session/answer-still-applies?`, which is where that decision lives
  and is tested. It is not here because this namespace reaches `ajax.core`
  through `et.tr.ui.api` and so cannot be loaded by the node suite at all, which
  made the branch untestable by its neighbours rather than by its nature.

  **It is also where `key-store/set-seals!` lands**, which is what makes a key
  unusable by a user who is not armed. The flag is dropped to `false` *before*
  the request goes out and set only from the answer, so the window in which
  this browser does not yet know whether the current user seals is a window in
  which it does not seal. That is the safe direction: the server refuses prose
  into a sealed column, so the cost is a refused save, where the other default
  costs the F-1 bug. Doing it here rather than in each caller is what makes it
  hold for `switch-user`, for the dev picker, for the token restore, and for
  whatever asks next."
  [app-state auth-headers]
  (key-store/set-seals! false)
  (api/fetch-json "/api/auth/me" (auth-headers)
    (fn [user]
      (when (session/answer-still-applies? user (:current-user @app-state))
        (key-store/set-seals! (:seal_prose user))
        (swap! app-state update :current-user merge user)
        (apply-user-language (:current-user @app-state))
        (save-auth-to-storage (:token @app-state) (:current-user @app-state))))))

(defn fetch-auth-required [app-state auth-headers _initial-collection-state fetch-all-fn
                           & {:keys [on-skip-logins]}]
  ;; Both of these are unauthenticated by definition — they run before there is
  ;; a token to send — so `nil` headers, and they still go through the one door.
  (api/fetch-json "/api/auth/required" nil
    (fn [resp]
      (swap! app-state assoc :auth-required? (:required resp))
      (if-not (:required resp)
        (api/fetch-json "/api/auth/available-users" nil
          (fn [users]
            (let [regular-user (first (remove :is_admin users))
                  selected-user (or regular-user {:id nil :username "admin" :is_admin true :has_mail false :language "en"})]
              (swap! app-state assoc
                     :logged-in? true
                     :current-user selected-user
                     :available-users users)
              (apply-user-language selected-user)
              ;; `available-users` answers names and roles and no `seal_prose`;
              ;; only `/api/auth/me` knows who seals. Without this the key panel
              ;; is offered to nobody in dev, which is every browser in the box.
              (refresh-current-user app-state auth-headers)
              (fetch-all-fn selected-user))))
        (let [{:keys [token user]} (load-auth-from-storage)]
          (when (and token user)
            (swap! app-state assoc
                   :logged-in? true
                   :token token
                   :current-user user)
            (apply-user-language user)
            (fetch-all-fn user)
            (refresh-current-user app-state auth-headers)))))))

(defn login
  "Sign in, and then **ask who this is**.

  The login response carries the user row, and it is deliberately not trusted
  for `seal_prose`: the row holds a raw `0`/`1` and the admin branch answers a
  synthetic map with no flag at all, where `/api/auth/me` resolves it through
  `envelope/seals?` — the guard's own predicate, from the effective user id.
  One source, so the client's gate and the server's refusal cannot drift.

  Without the refresh here the flag would stay `false` for the whole session
  after a production login, and the armed user could never seal — the mirror
  image of F-1, made permanent instead of transient. `auth-headers` is taken
  for that call and for nothing else."
  [app-state auth-headers username password on-success]
  (api/post-json "/api/auth/login"
    {:username username :password password}
    nil
    (fn [resp]
      (let [user (:user resp)
            token (:token resp)]
        (swap! app-state assoc
               :logged-in? true
               :token token
               :current-user user
               :error nil)
        (save-auth-to-storage token user)
        (apply-user-language user)
        (refresh-current-user app-state auth-headers)
        (when on-success (on-success))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Invalid credentials")))))

(defn logout [app-state initial-collection-state]
  (clear-auth-from-storage)
  ;; The next person at this browser is not this one, and their writes must not
  ;; echo bytes remembered from somebody else's rows. The key itself is left
  ;; alone: forgetting it is a separate, deliberate act in the ⚙ panel, because
  ;; signing out is not the same as handing the machine over.
  ;;
  ;; **What does not survive is the entitlement to use it.** Leaving the key and
  ;; dropping the flag is the whole shape of the fix for F-1: the borrowed-machine
  ;; decision above is about the *key*, and it stays; whether the next person may
  ;; seal with it is a different question, and the answer while nobody is signed
  ;; in is no. `refresh-current-user` sets it again from `/api/auth/me`.
  (api/forget-stored!)
  (key-store/set-seals! false)
  (swap! app-state merge
         initial-collection-state
         {:logged-in? false
          :token nil
          :current-user nil
          :users []
          :active-tab :today}))

(defn update-user-language [app-state auth-headers language]
  (api/put-json "/api/user/language" {:language language} (auth-headers)
    (fn [_]
      (i18n/set-language! language)
      (swap! app-state update :current-user assoc :language language)
      (let [user (:current-user @app-state)
            token (:token @app-state)]
        (save-auth-to-storage token user)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update language")))))

(defn update-vim-keys [app-state auth-headers enabled]
  (api/put-json "/api/user/vim-keys" {:vim_keys (if enabled 1 0)} (auth-headers)
    (fn [_]
      (swap! app-state update :current-user assoc :vim_keys (if enabled 1 0))
      (let [user (:current-user @app-state)
            token (:token @app-state)]
        (save-auth-to-storage token user)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update setting")))))

(defn update-inverted-scope-placement [app-state auth-headers enabled]
  (api/put-json "/api/user/inverted-scope-placement"
    {:inverted_scope_placement (if enabled 1 0)} (auth-headers)
    (fn [_]
      (swap! app-state update :current-user assoc :inverted_scope_placement (if enabled 1 0))
      (let [user (:current-user @app-state)
            token (:token @app-state)]
        (save-auth-to-storage token user)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update setting")))))
