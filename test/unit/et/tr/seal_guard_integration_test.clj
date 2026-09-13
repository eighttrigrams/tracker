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
            [et.tr.auth :as auth]
            [et.tr.db :as db]
            [et.tr.db.user :as db.user]
            [et.tr.envelope :as envelope]
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
