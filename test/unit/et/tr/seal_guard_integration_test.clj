(ns et.tr.seal-guard-integration-test
  "The server half of *the client seals, the server enforces*.

  The server holds no key and cannot read a body. What it can do is the one thing
  no client can: know **whose** rows these are. Tracker has three humans in one
  database and only one of them seals, so a client deciding that from a username
  would be a guess, and a guess that seals somebody else's prose is not
  recoverable.

  The rule under test, and the wording is the whole of it:

  > For a user whose `seal_prose` is 1, a write may not **introduce** new
  > plaintext prose into a sealed column. An **echo** of the value already stored
  > is not an introduction."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [et.tr.auth :as auth]
            [et.tr.db :as db]
            [et.tr.db.user :as db.user]
            [et.tr.envelope :as envelope]
            [et.tr.middleware.rate-limit :as rate-limit]
            [et.tr.seal-rules :as rules]
            [et.tr.server]
            [et.tr.server.recording-mode :as recording-mode]
            [et.tr.integration-helpers :refer [*app* *ds* *user-id* GET-json
                                               POST-json PUT-json with-integration-db]]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [ring.mock.request :as mock]))

(use-fixtures :each with-integration-db)

(def ^:private fixture
  (delay (edn/read-string (slurp (io/file "test/fixtures/seal-vectors.edn")))))

(defn- an-envelope
  "A real ciphertext out of the shared fixture. The server cannot open it and
  does not try — what is under test is that it can tell one from prose."
  []
  (:sealed (first (:vectors @fixture))))

(defn- seal-user! [on?]
  (jdbc/execute-one! (db/get-conn *ds*)
                     (sql/format {:update :users
                                  :set {:seal_prose (if on? 1 0)}
                                  :where [:= :id *user-id*]})))

(defn- new-task! []
  (:id (:body (POST-json "/api/tasks" {:title "a task"}))))

(defn- stored-description [id]
  (:description (jdbc/execute-one! (db/get-conn *ds*)
                                   (sql/format {:select [:description] :from [:tasks]
                                                :where [:= :id id]})
                                   db/jdbc-opts)))

;; ---------------------------------------------------------------------------
;; The prefix is the one the clients use.

(deftest the-server-knows-the-same-prefix-the-clients-do
  (is (= (get-in @fixture [:envelope :prefix]) envelope/prefix))
  (is (true? (envelope/sealed? (an-envelope))))
  (doseq [p (:passthrough @fixture)]
    (is (false? (envelope/sealed? p)) (str p " must read as plaintext"))))

(deftest the-server-inventory-is-the-one-the-fixture-names
  (is (= (into {} (for [[t cs] (:sealed-columns @fixture)] [t (mapv keyword cs)]))
         envelope/sealed-columns))
  (is (not (envelope/sealed-table? :messages))
      "message bodies are written by three keyless producers and searched by body"))

;; ---------------------------------------------------------------------------
;; A user who does not seal is exactly what tracker was before any of this.

(deftest a-user-who-does-not-seal-writes-prose-freely
  (seal-user! false)
  (let [id (new-task!)
        resp (PUT-json (str "/api/tasks/" id) {:title "a task" :description "readable" :tags ""})]
    (is (= 200 (:status resp)))
    (is (= "readable" (stored-description id)))))

(deftest a-user-who-does-not-seal-may-not-be-handed-an-envelope
  (seal-user! false)
  (let [id (new-task!)
        resp (PUT-json (str "/api/tasks/" id) {:title "a task" :description (an-envelope) :tags ""})]
    (is (= 400 (:status resp)) "a keyed client must not seal somebody else's rows")
    (is (= "sealed" (get-in resp [:body :reason])))
    (is (= "" (stored-description id)) "and nothing was written")))

;; ---------------------------------------------------------------------------
;; A sealing user.

(deftest a-sealing-user-may-write-an-envelope
  (seal-user! true)
  (let [id (new-task!)
        resp (PUT-json (str "/api/tasks/" id) {:title "a task" :description (an-envelope) :tags ""})]
    (is (= 200 (:status resp)))
    (is (= (an-envelope) (stored-description id)))))

(deftest a-sealing-user-may-not-introduce-readable-text
  (seal-user! true)
  (let [id (new-task!)
        resp (PUT-json (str "/api/tasks/" id) {:title "a task" :description "readable" :tags ""})]
    (is (= 400 (:status resp)))
    (is (= "unsealed" (get-in resp [:body :reason])))
    (is (= "description" (get-in resp [:body :column])))
    (is (= "" (stored-description id)) "nothing written")))

(deftest a-blank-body-is-always-allowed
  (seal-user! true)
  (let [id (new-task!)]
    (testing "blank is never sealed, so a sealing user still writes one"
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id)
                                    {:title "a task" :description "" :tags ""}))))
      (is (= "" (stored-description id))))
    (testing "and whitespace-only counts as blank, as it does in the seal itself"
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id)
                                    {:title "a task" :description "  " :tags ""})))))))

(deftest an-echo-of-what-is-stored-is-not-an-introduction
  ;; This is the rule that keeps a half-migrated database usable. Before the
  ;; walker runs, every body is plaintext; a no-op save sends that plaintext
  ;; straight back, and refusing it would make every unmigrated row unsavable.
  (seal-user! false)
  (let [id (new-task!)]
    (PUT-json (str "/api/tasks/" id) {:title "a task" :description "not migrated yet" :tags ""})
    (seal-user! true)
    (let [resp (PUT-json (str "/api/tasks/" id)
                         {:title "a task" :description "not migrated yet" :tags ""})]
      (is (= 200 (:status resp)) "the same bytes that are already there")
      (is (= "not migrated yet" (stored-description id))))
    (testing "but changing it is an introduction, and refused"
      (is (= 400 (:status (PUT-json (str "/api/tasks/" id)
                                    {:title "a task" :description "edited in the clear" :tags ""}))))
      (is (= "not migrated yet" (stored-description id))))))

(deftest a-create-has-nothing-to-echo
  (seal-user! true)
  (let [resp (POST-json "/api/resources" {:title "r" :description "readable"})]
    (is (= 400 (:status resp))
        "no stored value can make this an echo, so it is an introduction")))

(deftest the-guard-covers-every-sealed-entity-and-not-messages
  (seal-user! true)
  (testing "an issue is guarded"
    (let [id (:id (:body (POST-json "/api/issues" {:title "an issue"})))]
      (is (= 400 (:status (PUT-json (str "/api/issues/" id)
                                    {:title "an issue" :description "readable" :tags ""}))))))
  (testing "a category is guarded, through whichever of its six URLs"
    (let [id (:id (:body (POST-json "/api/people" {:name "Andrew"})))]
      (is (= 400 (:status (PUT-json (str "/api/people/" id)
                                    {:name "Andrew" :description "readable" :tags ""}))))))
  (testing "a message is not"
    (let [id (:id (:body (POST-json "/api/messages" {:sender "a" :title "m"
                                                     :description "readable"})))]
      (is (some? id) "the inbox keeps filling, from three producers with no key"))))

(deftest a-write-that-carries-no-body-is-not-the-guard-s-business
  (seal-user! true)
  (let [id (new-task!)]
    (is (= 200 (:status (PUT-json (str "/api/tasks/" id) {:title "retitled" :tags "x"})))
        "an absent column is not a write to that column")))

;; ---------------------------------------------------------------------------
;; The two message conversions, which are the one write that deletes its own
;; original.
;;
;; A message body is plaintext by design and permanently — three producers that
;; hold no key write them. `convert-message-to-task` and
;; `convert-message-to-resource` copied that body into a **sealed** column and
;; then `DELETE`d the message in the same transaction.
;;
;; The acceptance argument that covers the `"t "` auto-convert does not reach
;; these two. That argument is *the message it was copied from is in the clear in
;; the same database, so sealing the copy protects nothing while the original
;; sits beside it* — and for these the original does not sit beside it. After the
;; convert the only copy of that prose in the database is readable, in a column
;; the whole feature exists to make unreadable on fly.
;;
;; So the client, which holds both the message body and the key, seals it and
;; sends it; and a convert that does not is refused rather than quietly allowed,
;; because there is no second chance at it.

(defn- a-message! [body]
  (:id (:body (POST-json "/api/messages" {:sender "the poller" :title "an article"
                                          :description body}))))

(defn- stored-description-of [table id]
  (:description (jdbc/execute-one! (db/get-conn *ds*)
                                   (sql/format {:select [:description] :from [table]
                                                :where [:= :id id]})
                                   db/jdbc-opts)))

(deftest a-convert-writes-the-sealed-body-the-client-sends
  (seal-user! true)
  (testing "to a task"
    (let [message (a-message! "a paragraph of his own notes")
          resp (POST-json (str "/api/messages/" message "/convert-to-task")
                          {:description (an-envelope)})]
      (is (= 200 (:status resp)))
      (is (= (an-envelope) (stored-description-of :tasks (:id (:body resp))))
          "the client's envelope, and not the message body copied")))
  (testing "to a resource"
    (let [message (a-message! "a paragraph of his own notes")
          resp (POST-json (str "/api/messages/" message "/convert-to-resource")
                          {:link "https://example.com/x" :description (an-envelope)})]
      (is (= 200 (:status resp)))
      (is (= (an-envelope) (stored-description-of :resources (:id (:body resp))))))))

(deftest a-sealing-user-s-convert-that-carries-no-body-is-refused
  ;; Not *allowed and sealed later* — there is no later. The message row is gone
  ;; in the same transaction, so there is no clear original to fall back on and
  ;; nothing to diff against. The one moment this can be got right is this one.
  (seal-user! true)
  (testing "to a task"
    (let [message (a-message! "a paragraph of his own notes")
          resp (POST-json (str "/api/messages/" message "/convert-to-task") {})]
      (is (= 400 (:status resp)))
      (is (= "unsealed" (:reason (:body resp))))
      (is (some? (:id (:body (GET-json (str "/api/messages/" message)))))
          "and nothing was written: the message is still in the inbox")))
  (testing "to a resource"
    (let [message (a-message! "a paragraph of his own notes")
          resp (POST-json (str "/api/messages/" message "/convert-to-resource")
                          {:link "https://example.com/x"})]
      (is (= 400 (:status resp)))
      (is (some? (:id (:body (GET-json (str "/api/messages/" message)))))))))

(deftest a-sealing-user-s-convert-whose-body-is-null-is-refused-like-an-absent-one
  ;; The guard and the writer must agree about what counts as *a body was
  ;; supplied*. The writer asks `(or description (:description message) "")`,
  ;; which steps straight over `nil` and copies the mail body across in the
  ;; clear; a guard that asks `contains?` says yes to `{"description": null}` and
  ;; waves that copy through — into a sealed column, with the message deleted in
  ;; the same transaction and nothing to recover it from.
  ;;
  ;; `nil` is not a near-miss shape, either. A client that builds the field from
  ;; the message it holds — `{:description (seal k … (:description msg))}` — gets
  ;; `nil` back for a message whose body is `nil`, because blank is handed
  ;; straight back unsealed. This is on the happy path of the browser half.
  (seal-user! true)
  (testing "to a task"
    (let [message (a-message! "a paragraph of his own notes")
          resp (POST-json (str "/api/messages/" message "/convert-to-task")
                          {:description nil})]
      (is (= 400 (:status resp)))
      (is (= "unsealed" (:reason (:body resp))))
      (is (some? (:id (:body (GET-json (str "/api/messages/" message)))))
          "and the message is still in the inbox, not deleted behind a null")))
  (testing "to a resource"
    (let [message (a-message! "a paragraph of his own notes")
          resp (POST-json (str "/api/messages/" message "/convert-to-resource")
                          {:link "https://example.com/x" :description nil})]
      (is (= 400 (:status resp)))
      (is (some? (:id (:body (GET-json (str "/api/messages/" message)))))))))

(deftest a-user-who-does-not-seal-may-still-convert-with-a-null-body
  ;; The other side of the same predicate: for everybody who does not seal, a
  ;; `nil` body still means *copy the message across*, which is what this always
  ;; did. The refusal is about sealing, not about the shape of the JSON.
  (seal-user! false)
  (let [message (a-message! "the mail body")
        resp (POST-json (str "/api/messages/" message "/convert-to-task")
                        {:description nil})]
    (is (= 200 (:status resp)))
    (is (= "the mail body" (stored-description-of :tasks (:id (:body resp)))))))

(deftest a-sealing-user-s-convert-may-not-carry-readable-prose-either
  (seal-user! true)
  (let [message (a-message! "a paragraph of his own notes")
        resp (POST-json (str "/api/messages/" message "/convert-to-task")
                        {:description "a paragraph of his own notes"})]
    (is (= 400 (:status resp))
        "a create has nothing to echo, so plaintext here is an introduction")))

(deftest a-convert-of-an-empty-message-needs-no-body
  ;; A link-only message from the feed worker is the common case, and blank is
  ;; never sealed. Requiring an envelope for a body that does not exist would
  ;; make the commonest convert in the app impossible.
  (seal-user! true)
  (let [message (a-message! "")
        resp (POST-json (str "/api/messages/" message "/convert-to-task")
                        {:description ""})]
    (is (= 200 (:status resp)))
    (is (= "" (stored-description-of :tasks (:id (:body resp)))))))

(deftest a-user-who-does-not-seal-converts-exactly-as-before
  ;; The behaviour every other user in this database has, and the behaviour the
  ;; whole app had before the seal: the server copies the message body across.
  ;; Nothing about this change may reach `antonio`.
  (seal-user! false)
  (testing "to a task"
    (let [message (a-message! "the mail body")
          resp (POST-json (str "/api/messages/" message "/convert-to-task") {})]
      (is (= 200 (:status resp)))
      (is (= "the mail body" (stored-description-of :tasks (:id (:body resp)))))))
  (testing "to a resource"
    (let [message (a-message! "the mail body")
          resp (POST-json (str "/api/messages/" message "/convert-to-resource")
                          {:link "https://example.com/y"})]
      (is (= 200 (:status resp)))
      (is (= "the mail body" (stored-description-of :resources (:id (:body resp)))))))
  (testing "and an envelope from a keyed client is refused, as everywhere else"
    (let [message (a-message! "the mail body")]
      (is (= 400 (:status (POST-json (str "/api/messages/" message "/convert-to-task")
                                     {:description (an-envelope)}))))))
  (testing "and the plaintext body the browser now sends is written as it stands,
    which is the path **every user who does not seal** takes since R-3's client
    half. `convert-params` hands the body over on every convert, keyed or not, so
    what used to be a server-side copy of `messages.description` is now the same
    text arriving in the request. The row must come out identical either way, and
    for everybody except the one armed user this is the only convert there is."
    (let [message (a-message! "the mail body")
          resp (POST-json (str "/api/messages/" message "/convert-to-task")
                          {:description "the mail body"})]
      (is (= 200 (:status resp)))
      (is (= "the mail body" (stored-description-of :tasks (:id (:body resp)))))
      (is (nil? (:id (:body (GET-json (str "/api/messages/" message)))))
          "and the original is consumed, as it always was"))))

;; ---------------------------------------------------------------------------
;; Who seals, told to the client that has to know.
;;
;; The ⚙ key panel is not for everybody. Its own docstring says so — *"Tracker
;; has other people in it, and a key box on their settings page is an invitation
;; to a mistake they have no reason to be able to make"* — and it was gated on
;; `is_admin`, which is a different question with a different answer. `admin` is
;; a synthetic superuser row with `:id nil`; `antonio` and `saiyuri` are both
;; non-admins, so both were shown the box, which is exactly the population the
;; docstring excludes.
;;
;; The gate it wants is `seal_prose`, and until now no response carried it, so
;; the gate was not implementable at all. `/api/auth/me` is the door: its whole
;; reason for existing is to refresh DB-sourced settings rather than trust the
;; client's cached copy, and `seal_prose` is the setting that changes furthest
;; out-of-band of them all — the walker's `--arm` flips it in the database while
;; the browser is open.
;;
;; It is resolved through `envelope/seals?`, the same function the guard asks, so
;; that the answer the client is given and the answer the server enforces cannot
;; drift apart. That is not tidiness: a machine user's own row is never armed, and
;; a naive read of it would tell `daniel`'s CLI that it does not seal while every
;; write it makes is refused for being unsealed.

(defn- me-as
  "`GET /api/auth/me` as a real bearer token, which is what a browser holds once
  there is a login to hold one from.

  It used to say that the `X-User-Id` shortcut *could not reach this endpoint at
  all*, because the skip-logins path carries no `:username` and the handler
  looked the row up by name. That was true, and the sentence was where the cost
  of it stopped being followed: a dev session got a 404 from the one endpoint
  that answers `seal_prose`, so the panel the flag exists to gate could not have
  worked in the mode this app is developed in. Both doors are open now, and
  `the-flag-reaches-a-dev-session-too-…` holds the other one open."
  [claims]
  (-> (*app* (-> (mock/request :get "/api/auth/me")
                 (mock/header "Authorization" (str "Bearer " (auth/create-token claims)))))
      (update :body #(when (seq %) (json/read-str % :key-fn keyword)))))

(defn- human-claims [id username]
  {:user-id id :username username :is-admin false :has-mail true})

(deftest the-current-user-is-told-whether-its-prose-is-sealed
  (let [me (human-claims *user-id* "test-user")]
    (testing "false while the flag is 0, which is how 074-add-seal-prose ships"
      (seal-user! false)
      (is (= 200 (:status (me-as me))))
      (is (false? (:seal_prose (:body (me-as me))))))
    (testing "and true once the cutover arms it — read fresh from the database
      every time, because `--arm` flips it while the browser is open and the
      client's cached copy is the thing this endpoint exists not to trust"
      (seal-user! true)
      (is (true? (:seal_prose (:body (me-as me))))))
    (testing "a second human in the same database is not told he seals, and this
      is the whole of S-2: `is_admin` answered a different question and answered
      it wrongly for every non-admin human in the file"
      (seal-user! true)
      (let [other (db.user/create-user *ds* "antonio" "pw")]
        (is (false? (:seal_prose (:body (me-as (human-claims (:id other) "antonio")))))
            "his rows are not sealed, and a key box on his settings page is an
             invitation to a mistake he has no reason to be able to make")))
    (testing "a machine user reports its **parent's** flag, because that is the
      one the guard enforces against it — `claims->identity` collapses a machine
      user onto the user it acts for before anything asks"
      (seal-user! true)
      (let [machine (db.user/create-user *ds* "daniel-cli" "pw"
                                         {:is-machine-user true :for-user-id *user-id*})
            body (:body (me-as {:user-id (:id machine) :username "daniel-cli"
                                :is-admin false :has-mail false
                                :is-machine-user true
                                :for-user-id *user-id*
                                :mail-only false}))]
        (is (true? (:seal_prose body))
            "its own row is never armed, and reading that row would tell the CLI
             it does not seal while every write it makes is refused for being
             unsealed")
        ;; **The other half, and it had no test until a mutation probe asked.**
        ;; `seal_prose` comes from the *effective* id; everything else must keep
        ;; coming from the caller's own row. `me-handler` resolves the row by name
        ;; when there is one and falls back to the id only when there is not, and
        ;; the fallback is what makes a dev session work at all — so the
        ;; preference is the part a reader would delete as redundant. It is not:
        ;; delete it and a machine user is handed its parent's identity, which is
        ;; a different username and a different row, and 600 tests stayed green
        ;; while I checked.
        (is (= "daniel-cli" (:username body))
            "its own name, not the human's it acts for")
        (is (= (:id machine) (:id body))
            "and its own row — the id is the one thing that says which")
        (is (true? (:is_machine_user body)))))))

(deftest the-flag-reaches-a-dev-session-too-which-is-the-only-kind-in-this-box
  ;; `/api/auth/me` is the only place `seal_prose` is answered, so a client that
  ;; gates the ⚙ panel on it has to be able to ask **in the mode the app is
  ;; actually run in here**. Under `:dangerously-skip-logins?` there is no token
  ;; and no username: `get-user-from-request` answers a user *id*, and this
  ;; handler looked the row up by name and 404'd on every dev session.
  ;;
  ;; So the door existed and did not open. The flag was reachable only from a
  ;; production login — the one place nobody in this box was ever going to try —
  ;; and S-2's client half could not have been built against it even in
  ;; principle. The helper below says as much in its own docstring; what was
  ;; missing was following that sentence out to what it costs.
  (seal-user! true)
  (let [resp (GET-json "/api/auth/me")]
    (is (= 200 (:status resp)))
    (is (= "test-user" (:username (:body resp)))
        "resolved from the effective user id, since that is all a dev session has")
    (is (true? (:seal_prose (:body resp)))))
  (testing "and it is still read fresh, not cached anywhere"
    (seal-user! false)
    (is (false? (:seal_prose (:body (GET-json "/api/auth/me")))))))

(deftest a-convert-whose-body-never-parsed-is-refused-rather-than-thrown-at
  ;; A request whose content type kept `wrap-json-body` from parsing it arrives
  ;; here with an InputStream where the map should be. Every other branch of the
  ;; guard is behind `(map? body)`; the convert branch is not, because a convert
  ;; must be judged even when it carries nothing at all. So the question it asks
  ;; has to be one an InputStream can be asked — `get` answers `nil`, which is
  ;; the right answer, where `contains?` throws and the caller sees a 500.
  (seal-user! true)
  (let [message (a-message! "a paragraph of his own notes")
        resp (*app* (-> (mock/request :post (str "/api/messages/" message "/convert-to-task"))
                        (mock/header "X-User-Id" (str *user-id*))
                        (mock/header "Content-Type" "text/plain")
                        (mock/body "description=nope")))]
    (is (= 400 (:status resp)))
    (is (some? (:id (:body (GET-json (str "/api/messages/" message)))))
        "and the message is still in the inbox")))

;; ---------------------------------------------------------------------------
;; The write that never reaches the guard.
;;
;; `wrap-machine-write-guard` sits **outside** `wrap-seal-guard` — outside
;; `wrap-json-body`, even — so when it drops a machine write it short-circuits
;; before the guard is ever consulted. The dropped write is not written to its
;; table, and that is the whole of what "dropped" was taken to mean; but its
;; **body is kept**, verbatim, in `events.payload`, under the *parent* user's
;; id. So the one user this feature exists for gets his own prose parked in his
;; own event log, in the clear, behind a `200 {"dropped":true}`, with nothing
;; refusing it and nothing saying it happened.
;;
;; This is not a hypothetical route. `resources/tracker/api-usage.md` tells
;; machine clients that creating a task takes two writes and that both hit this
;; gate, so a drop is the documented normal case; the dev database holds twenty
;; of them, two aimed at sealed tables under the one sealing human.
;;
;; The fix is in `recording-mode/record-dropped-event!` and not in the guard,
;; because moving the guard outward would mean judging an unparsed body.

(defn- ensure-recording-off! []
  (when (recording-mode/enabled?) (recording-mode/toggle!)))

(defn- ensure-recording-on! []
  (when-not (recording-mode/enabled?) (recording-mode/toggle!)))

(defn- machine-token!
  "A real bearer token for a machine user of `*user-id*`, which is the only way
  to reach `wrap-machine-write-guard` at all — it asks `auth/verify-token`
  directly and the `X-User-Id` dev shortcut never carries machine claims."
  ([] (machine-token! false))
  ([mail-only?]
   (let [m (db.user/create-user *ds* (str "daniel-cli-" (System/nanoTime)) "pw"
                                {:is-machine-user true :for-user-id *user-id*})]
     (auth/create-token {:user-id (:id m) :username "daniel-cli" :is-admin false
                         :has-mail mail-only? :is-machine-user true
                         :for-user-id *user-id* :mail-only mail-only?}))))

(defn- machine-write
  "One mutating /api/* call as that machine user. `body` is sent as JSON unless
  it is already a string, in which case it goes out as-is under whatever
  content type is given — the guard reads the body before `wrap-json-body`, so
  an unparseable one reaches it exactly as it left the client."
  ([token method path body] (machine-write token method path body "application/json"))
  ([token method path body content-type]
   (-> (*app* (-> (mock/request method path)
                  (mock/header "Authorization" (str "Bearer " token))
                  (mock/header "Content-Type" content-type)
                  (mock/body (if (string? body) body (json/write-str body)))))
       (update :body #(when (seq %) (json/read-str % :key-fn keyword))))))

(defn- dropped-rows []
  (jdbc/execute! (db/get-conn *ds*)
                 (sql/format {:select [:effective_user_id :payload]
                              :from [:events]
                              :where [:= :dropped 1]
                              :order-by [[:id :asc]]})
                 db/jdbc-opts))

(defn- the-dropped-row []
  (let [rows (dropped-rows)]
    (is (= 1 (count rows)) "exactly one drop was recorded")
    (first rows)))

(def ^:private his-prose
  "The sentence under test. It is deliberately long and unmistakable so that the
  assertion can be *is this readable anywhere in the stored payload*, in the raw
  JSON text, rather than at a path some future payload shape might move."
  "a paragraph of his own notes, which is exactly what fly must not be able to read")

(deftest a-dropped-machine-write-leaves-no-readable-prose-in-the-event-log
  (seal-user! true)
  (ensure-recording-off!)
  (let [resp (machine-write (machine-token!) :post "/api/tasks"
                            {:title "a task" :description his-prose})]
    (is (= 200 (:status resp)))
    (is (true? (:dropped (:body resp)))
        "the guard is never consulted — the write is dropped above it")
    (let [row (the-dropped-row)]
      (is (= *user-id* (:effective_user_id row))
          "and it lands in the sealing human's log, as `claims->identity` intends")
      (is (not (str/includes? (:payload row) his-prose))
          "his prose is readable on fly, in the log of the one user the seal is for"))))

(deftest a-dropped-write-keeps-everything-that-is-not-prose
  ;; Withholding the whole body would have been the cheaper fix and it costs the
  ;; log the part worth keeping. Both drops that the live database actually holds
  ;; against a sealed table carry exactly these fields and no description at all
  ;; — `{"title":"New rhizome issue","scope":"both"}` and `{"url":"…"}` — so a
  ;; fix that threw them away would have emptied the audit trail to fix a leak
  ;; that had not happened yet in it.
  (seal-user! true)
  (ensure-recording-off!)
  (machine-write (machine-token!) :post "/api/issues"
                 {:title "New rhizome issue" :scope "both" :description his-prose})
  (let [payload (json/read-str (:payload (the-dropped-row)) :key-fn keyword)
        body (json/read-str (:body payload) :key-fn keyword)]
    (is (= "New rhizome issue" (:title body)))
    (is (= "both" (:scope body)) "the non-prose fields are the audit value")
    (is (not= his-prose (:description body)))
    (is (str/includes? (:description body) "sealed")
        "and what replaced it says why, rather than being blanked — a blank
         would read as a write that carried no body")
    (is (true? (:body-redacted payload))
        "the payload says of itself that the string is not what arrived")
    (is (= "recording-off" (:reason payload)))
    (is (= "/api/issues" (:uri payload)))))

(deftest every-segment-the-vocabulary-names-is-redacted-and-not-only-tasks
  ;; Driven off `api-segment->table` itself rather than a hand-written list, so
  ;; that a segment added there is covered here the day it is added — a list
  ;; copied out of a vocabulary is the failure this whole file keeps meeting.
  ;;
  ;; The assertion is deliberately *redacted*, not merely *not leaked*:
  ;; withholding would also pass a no-prose check, and the difference between
  ;; the two is exactly what a segment silently falling out of the map would
  ;; look like.
  (seal-user! true)
  (ensure-recording-off!)
  (let [token (machine-token!)]
    (doseq [segment (keys rules/api-segment->table)]
      (machine-write token :post (str "/api/" segment)
                     {:title "a thing" :description his-prose}))
    (let [rows (dropped-rows)]
      (is (= (count rules/api-segment->table) (count rows)))
      (doseq [row rows]
        (let [payload (json/read-str (:payload row) :key-fn keyword)]
          (is (not (str/includes? (:payload row) his-prose))
              (str "readable prose survived a dropped write to " (:uri payload)))
          (is (true? (:body-redacted payload))
              (str (:uri payload) " was withheld rather than redacted, which "
                   "means `endpoint-table` could not resolve it"))
          (is (= "a thing" (:title (json/read-str (:body payload) :key-fn keyword)))))))))

(deftest a-dropped-update-is-the-same-write-and-the-same-answer
  (seal-user! true)
  (ensure-recording-off!)
  (machine-write (machine-token!) :put "/api/tasks/1"
                 {:title "a task" :description his-prose})
  (is (not (str/includes? (:payload (the-dropped-row)) his-prose))))

(deftest a-mail-only-machine-user-is-dropped-with-recording-on-and-redacted-too
  ;; The other door into the same room, and the one that does not depend on the
  ;; toggle at all: a mail-only machine user is dropped on every non-mail
  ;; endpoint whether or not the human has recording on, so this route is live
  ;; in the state tracker normally runs in.
  (seal-user! true)
  (ensure-recording-on!)
  (try
    (machine-write (machine-token! true) :post "/api/tasks"
                   {:title "a task" :description his-prose})
    (let [row (the-dropped-row)]
      (is (= "mail-only" (:reason (json/read-str (:payload row) :key-fn keyword))))
      (is (not (str/includes? (:payload row) his-prose))))
    (finally (ensure-recording-off!))))

(deftest a-user-who-does-not-seal-has-the-payload-he-always-had
  ;; The whole of the non-sealing path, asserted on the bytes. Tracker for
  ;; antonio and saiyuri is what it was, and this fix may not be visible to them
  ;; in any form — not a redaction, not a flag, not a reordered key.
  (seal-user! false)
  (ensure-recording-off!)
  (let [sent {:title "a task" :description his-prose}]
    (machine-write (machine-token!) :post "/api/tasks" sent)
    (let [raw (:payload (the-dropped-row))
          payload (json/read-str raw :key-fn keyword)]
      (is (= (json/write-str sent) (:body payload))
          "his body, verbatim, exactly as it arrived")
      (is (not (contains? payload :body-redacted)))
      (is (not (contains? payload :body-withheld)))
      (is (= ["method" "uri" "body" "reason"] (vec (keys (json/read-str raw))))
          "and in the order this payload has always had, so a reader of old rows
           and new ones is reading one shape"))))

(deftest an-already-sealed-body-is-kept-as-it-arrived
  ;; The decision this fix had to make. A key-holding client sends ciphertext;
  ;; fly cannot open it in `events.payload` any more than in `tasks.description`,
  ;; so redacting it would spend audit value on nothing. `sealed?` is the whole
  ;; of the question, and it is the same prefix test the guard uses.
  (seal-user! true)
  (ensure-recording-off!)
  (let [sent {:title "a task" :description (an-envelope)}]
    (machine-write (machine-token!) :post "/api/tasks" sent)
    (let [payload (json/read-str (:payload (the-dropped-row)) :key-fn keyword)]
      (is (= (json/write-str sent) (:body payload))
          "the envelope, verbatim — it is already unreadable")
      (is (not (contains? payload :body-redacted))))))

(deftest a-blank-body-is-not-claimed-to-have-been-removed
  ;; Rule 1 one level up: a blank is never prose, so redacting one would have the
  ;; payload assert that something was taken out of a write that carried nothing.
  (seal-user! true)
  (ensure-recording-off!)
  (let [sent {:title "a task" :description "   "}]
    (machine-write (machine-token!) :post "/api/tasks" sent)
    (let [payload (json/read-str (:payload (the-dropped-row)) :key-fn keyword)]
      (is (= (json/write-str sent) (:body payload)))
      (is (not (contains? payload :body-redacted))))))

(deftest a-body-that-will-not-parse-is-withheld-whole-rather-than-guessed-at
  (seal-user! true)
  (ensure-recording-off!)
  (machine-write (machine-token!) :post "/api/tasks"
                 (str "{\"title\":\"a task\",\"description\":\"" his-prose)
                 "application/json")
  (let [raw (:payload (the-dropped-row))
        payload (json/read-str raw :key-fn keyword)]
    (is (not (str/includes? raw his-prose)))
    (is (not (contains? payload :body)) "no body key at all, not a blank one")
    (is (string? (:body-withheld payload)) "and it says why")))

(deftest an-endpoint-the-vocabulary-does-not-know-is-withheld
  ;; The fallback, now that it applies only to a genuine unknown. `/api/relations`
  ;; is a real write route that neither segment map classifies — nobody had to
  ;; classify it, because no handler under it touches a sealed column — so
  ;; `endpoint-disposition` answers nothing and the body is kept none of.
  (seal-user! true)
  (ensure-recording-off!)
  (machine-write (machine-token!) :post "/api/relations"
                 {:from "task" :description his-prose})
  (let [payload (json/read-str (:payload (the-dropped-row)) :key-fn keyword)]
    (is (nil? (rules/endpoint-disposition "/api/relations"))
        "and it really is unknown, rather than known-and-clear")
    (is (not (contains? payload :body)))
    (is (string? (:body-withheld payload)))))

(deftest a-message-drop-keeps-its-body-because-messages-is-known-to-be-clear
  ;; What the third answer buys back. `messages` is permanently clear — three
  ;; keyless producers write those bodies and `clear-tables` says so — and the
  ;; same prose sits in `messages.description` in the clear in the same database,
  ;; so withholding the log's copy protected nothing and cost the audit trail
  ;; eighteen of the twenty drops the live database holds.
  (seal-user! true)
  (ensure-recording-off!)
  (let [sent {:sender "the poller" :title "an article" :description his-prose}]
    (machine-write (machine-token!) :put "/api/messages/999" sent)
    (let [payload (json/read-str (:payload (the-dropped-row)) :key-fn keyword)]
      (is (= (json/write-str sent) (:body payload))
          "verbatim: a clear table's prose is not this feature's business")
      (is (not (contains? payload :body-redacted)))
      (is (not (contains? payload :body-withheld)))))
  (testing "and a motto, for the same reason and a different argument"
    (machine-write (machine-token!) :post "/api/mottos"
                   {:title "Memento Mori" :description "Remember death"})
    (is (some #(str/includes? (:payload %) "Remember death") (dropped-rows)))))

(deftest a-convert-is-still-sealed-though-it-sits-at-a-messages-url
  ;; The ordering inside `endpoint-disposition`. Ask the segment maps before
  ;; `convert-target` and this comes back *clear* — a message URL — which is the
  ;; one wrong answer available, because the row it creates is a task.
  (seal-user! true)
  (ensure-recording-off!)
  (is (= {:table :tasks :sealed? true}
         (rules/endpoint-disposition "/api/messages/7/convert-to-task")))
  (machine-write (machine-token!) :post "/api/messages/7/convert-to-task"
                 {:title "from the inbox" :description his-prose})
  (is (not (str/includes? (:payload (the-dropped-row)) his-prose))))

(deftest a-dropped-conversion-is-resolved-the-way-the-guard-resolves-it
  ;; A convert writes into `tasks` while sitting at a `messages` URL, so
  ;; `endpoint-table` alone would withhold it. `convert-target` is the question
  ;; the guard asks about the same request, and asking it here keeps the two in
  ;; step and keeps the conversion's non-prose fields.
  (seal-user! true)
  (ensure-recording-off!)
  (machine-write (machine-token!) :post "/api/messages/1/convert-to-task"
                 {:title "from the inbox" :description his-prose})
  (let [payload (json/read-str (:payload (the-dropped-row)) :key-fn keyword)]
    (is (not (str/includes? (:payload (the-dropped-row)) his-prose)))
    (is (= "from the inbox" (:title (json/read-str (:body payload) :key-fn keyword)))
        "and the rest of it survives, because the table was resolvable")))

(deftest the-walker-and-verify-still-read-both-shapes-correctly
  ;; `prose-paths` is what the walker seals by and what `--verify` reads by, and
  ;; `clear-entity-type?` says a dropped event is in scope for both. A redacted
  ;; body is still a string, so it still takes the `event/body` path and is
  ;; sealed on the next pass exactly as an unredacted one was. A withheld one has
  ;; no `:body` key, so it yields no path at all — which is the only reason
  ;; withholding is safe to do without touching the walker.
  (is (false? (rules/clear-entity-type? "dropped"))
      "a plaintext body here is a violation `--verify` must still see")
  (is (= [[[:body] rules/event-body-aad]]
         (rules/prose-paths {:method "POST" :uri "/api/tasks"
                             :body "{\"title\":\"a task\"}" :reason "recording-off"})))
  (is (= [] (rules/prose-paths {:method "POST" :uri "/api/tasks"
                                :body-withheld "…" :reason "recording-off"}))
      "nothing to seal, and nothing for --verify to call a violation"))

(deftest the-production-middleware-stack-answers-the-same-way
  ;; Everything above this runs through `integration-helpers/make-app`, which
  ;; says of itself that it is *a hand-kept copy* of `et.tr.server`'s stack — and
  ;; the copy stops after `wrap-machine-write-guard`, leaving out
  ;; `audit/wrap-write-audit`, `wrap-auth` and `machine-lean/wrap-machine-lean`.
  ;;
  ;; For this finding the omission is harmless, and reading is how one knows it:
  ;; all three sit **outside** the write guard in `server/app`, `wrap-write-audit`
  ;; only logs, `wrap-auth` only ever refuses (and not in dev), and
  ;; `machine-lean` rewrites responses and leaves requests alone. But *harmless
  ;; by reading* is what the copy costs every finding that lands near it, so this
  ;; one builds the real stack and asks it the same question. A middleware added
  ;; outside the guard that consumed the body, or moved the guard, would fail
  ;; here and pass everywhere above.
  (rate-limit/reset-rate-limit!)
  (seal-user! true)
  (ensure-recording-off!)
  (let [production (#'et.tr.server/app false)
        resp (production (-> (mock/request :post "/api/tasks")
                             (mock/header "Authorization" (str "Bearer " (machine-token!)))
                             (mock/header "Content-Type" "application/json")
                             (mock/body (json/write-str {:title "a task"
                                                         :description his-prose}))))]
    (is (= 200 (:status resp)))
    (is (= {:dropped true} (json/read-str (:body resp) :key-fn keyword))
        "the guard is still never reached in production order either")
    (let [row (the-dropped-row)]
      (is (= *user-id* (:effective_user_id row)))
      (is (not (str/includes? (:payload row) his-prose))))))
