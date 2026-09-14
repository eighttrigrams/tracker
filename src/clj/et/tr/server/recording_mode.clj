(ns et.tr.server.recording-mode
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [taoensso.telemere :as tel]
            [et.tr.auth :as auth]
            [et.tr.db.event :as db.event]
            [et.tr.db.user :as db.user]
            [et.tr.envelope :as envelope]
            [et.tr.seal-rules :as rules]
            [et.tr.server.common :as common]))

(defonce ^:private *recording? (atom false))

(defn enabled? [] @*recording?)

(defn toggle! [] (swap! *recording? not))

(defn- mutating? [req] (#{:post :put :delete} (:request-method req)))

(defn- api-request? [req]
  (str/starts-with? (or (:uri req) "") "/api/"))

(defn- gate-exempt?
  "Endpoints that machine users may always write to, regardless of recording
  mode. Mail ingestion lives here so the inbox keeps filling even when the
  human hasn't enabled recording — those messages still need triage in the UI
  before they affect anything."
  [req]
  (let [uri (or (:uri req) "")]
    (and (= :post (:request-method req))
         (or (= uri "/api/messages")
             (= uri "/api/messages/")))))

(defn- machine-claims [req]
  (when-let [token (auth/extract-token req)]
    (when-let [claims (auth/verify-token token)]
      (when (:is-machine-user claims) claims))))

(defn- read-body-string
  "Best-effort read of the request body as a string. Returns nil if the
  body has already been consumed or is non-readable. The middleware runs
  before wrap-json-body, so the body is still a stream here; we slurp it
  for the audit trail (and discard since we are about to short-circuit
  the request anyway)."
  [req]
  (try
    (when-let [b (:body req)]
      (cond
        (string? b) b
        (instance? java.io.InputStream b) (slurp b)
        :else (str b)))
    (catch Throwable _ nil)))

;; ---------------------------------------------------------------------------
;; What may be kept of a dropped write's body.
;;
;; This middleware sits **outside** `envelope/wrap-seal-guard` — outside
;; `wrap-json-body`, even — and when it drops a write it short-circuits before
;; the guard is ever reached. So the one rule the server exists to enforce,
;; *a sealing user's prose may not arrive in the clear*, is not enforced on this
;; path; and this path does not discard the body, it **files** it, verbatim, in
;; `events.payload`, under the parent human's `effective_user_id`. A keyless
;; machine client writing a description with recording off therefore got a
;; `200 {"dropped":true}` and left its sentence readable on fly, in the event
;; log of the only user the seal is for.
;;
;; The fix is here and not in the middleware order. Moving the guard outward
;; would mean judging a body that `wrap-json-body` has not parsed yet, and the
;; guard's whole shape depends on having the parsed map; the drop is also not a
;; write it could refuse, since there is nothing to refuse — the row never
;; happens. What has to change is what the *audit trail* is allowed to remember.

(def ^:private prose-removed
  "What replaces a readable sealed column in a dropped write's captured body.

  A sentence and not `nil` or `\"\"`, because a reader of the log — a person in
  the ⚙ history tab, a later pass over `events` — needs to be able to tell *this
  write carried a body and it was deliberately not kept* apart from *this write
  carried no body*. A blank would collapse those two, which is the same mistake
  `rules/blank-value?` exists to prevent one level down."
  "<removed: this user's prose is sealed, and this write was dropped>")

(def ^:private body-withheld-reason
  "Why the whole body is gone, when the safe answer is to keep none of it. Both
  causes are in it, because a reader finding this in the log wants to know which:
  an endpoint the seal's vocabulary does not classify, or a body it cannot parse."
  "this user's prose is sealed, and neither this endpoint nor this body could be read well enough to remove it")

(defn- readable-prose-in
  "Which of `table`'s sealed columns this parsed body carries **readable** prose
  in — which is, exactly, the set `wrap-seal-guard` would have refused had it
  been reached. The two functions are deliberately asking one question.

  Two kinds of value are left alone, and for the same reason in both cases:
  replacing them would make the payload assert something untrue about the write
  that produced it.

  - **Blank** is never prose (`rules/blank-value?`, rule 1), so a
    `{\"description\": \"\"}` that is redacted would claim prose was removed
    where there was none. Non-strings fall under the same predicate and the same
    argument, and so does an **absent** column — `nil` is blank, which is why
    there is no separate `contains?` test here as there is in the guard.

  - **Already sealed** is the decision this fix had to make, and the answer is
    *leave it*. A body that arrives carrying `enc:v1:…` came from a client that
    holds the key; the ciphertext is exactly as unreadable in `events.payload`
    as it is in `tasks.description`, and fly cannot open either. Redacting it
    would spend real audit value — the drop could no longer be replayed or even
    shown to have carried a body — to buy nothing, since the value it removed
    was already unreadable. It is also the case the rest of the seal is built
    for: `prose-paths` finds this body, the walker seals it, and `--verify`
    reads it, all unchanged. `rules/sealed?` is the whole of the question, and
    it is the same prefix test the guard uses to decide the same thing."
  [table body]
  (filter #(let [v (get body %)]
             (and (not (rules/blank-value? v))
                  (not (rules/sealed? v))))
          (get rules/sealed-columns table)))

(defn- kept-body
  "What may be kept of `raw` for a user whose prose is sealed: the same JSON with
  its readable prose replaced, the original string when there was none to
  replace, or `::withhold` when the question cannot be answered safely.

  **Redact, with withhold as the fallback.** The non-prose fields are the audit
  value — `title`, `scope`, `url` are what the two real dropped writes in the
  live database carry, and they are what makes a dropped event worth having at
  all. So they are kept, and only the columns `rules/sealed-columns` names for
  this table are taken out.

  **Keep a clear table's body whole.** `rules/endpoint-disposition` answers three
  ways, and the middle answer is the one this function needs most: `messages` and
  `mottos` are *known, and known to carry no sealed prose*. The same text sits in
  `messages.description` in the clear in the same database, written there by
  three producers that hold no key, so removing the log's copy protects nothing
  at all while the original sits beside it — the argument `rules/clear-tables`
  already makes about the column itself.

  That answer is new, and its absence was expensive. While `endpoint-table` was
  the only question available, `nil` meant both *clear* and *unclassified*, this
  function had to read it as the second, and every `PUT /api/messages/:id` a
  sealing user's mail machine dropped lost its body — eighteen of the twenty
  dropped events the live database holds, eight of them carrying prose that was
  legitimately readable.

  **Withhold only when the endpoint is genuinely unknown**, or when the body
  will not parse, or parses to something other than a map. Those are the cases
  where nothing here knows where the prose is, and a guess is the one thing that
  must not happen. `::withhold` keeps none of it.

  Resolution is `endpoint-disposition`'s, which puts `convert-target` ahead of
  both segment maps — a message conversion writes into `tasks` while sitting at a
  `messages` URL, and asking the maps first would call it clear."
  [uri raw]
  (let [{:keys [table sealed?]} (rules/endpoint-disposition uri)
        parsed (when (and table (string? raw))
                 (try (json/read-str raw :key-fn keyword)
                      (catch Throwable _ nil)))]
    (cond
      (not (map? parsed)) ::withhold
      (not sealed?) raw
      :else (let [columns (readable-prose-in table parsed)]
              (if (empty? columns)
                raw
                (json/write-str (reduce #(assoc %1 %2 prose-removed) parsed columns)))))))

(defn- seals-or-cannot-say?
  "Whether this drop's body has to be dealt with at all.

  `envelope/seals?` asked about the **effective** user, which is the id this
  event is filed under and therefore the one whose log the prose would sit in.
  A machine user's own row is never armed, so asking about the caller would
  answer no for every write `daniel`'s CLI makes.

  Two non-answers count as yes. A machine token with no `for-user-id` is
  malformed, and `db.event` files an event with no `effective_user_id` as a
  system event, which `list-events-for-user` shows to **every** user — so an
  unattributable body is the last one to keep. A throw means the database is
  not answering, and the choice then is between withholding and leaking, not
  between withholding and working."
  [ds parent-id]
  (if (nil? parent-id)
    true
    (try (envelope/seals? ds parent-id)
         (catch Throwable _ true))))

(defn- body-entry
  "The `:body` (or `:body-withheld`) part of a dropped write's payload.

  **A user who does not seal gets today's payload, byte for byte** — the same
  key, the same verbatim string, in the same position, so nothing about tracker
  as it is now changes. The extra `seal_prose` read happens only when there is a
  body to have an opinion about."
  [ds parent-id req]
  (let [raw (read-body-string req)]
    (cond
      (str/blank? raw) {:body raw}
      (not (seals-or-cannot-say? ds parent-id)) {:body raw}
      :else (let [kept (kept-body (:uri req) raw)]
              (if (= ::withhold kept)
                ;; No `:body` key at all, deliberately: `rules/prose-paths`
                ;; matches this payload shape on `(string? (:body payload))`, so
                ;; a withheld body yields no path, and the walker and `--verify`
                ;; stay correct with no change to either.
                {:body-withheld body-withheld-reason}
                (cond-> {:body kept}
                  (not= kept raw) (assoc :body-redacted true)))))))

(defn- record-dropped-event! [req claims reason]
  (try
    (let [ds (common/ensure-ds)
          parent-id (:for-user-id claims)
          parent-username (when parent-id
                            (:username (db.user/get-user-by-id ds parent-id)))]
      (db.event/record-event!
       ds
       {:actor-user-id (:user-id claims)
        :actor-username (or (:username claims) "machine")
        :is-machine? true
        :parent-user-id parent-id
        :parent-username parent-username}
       {:entity-type :dropped
        :entity-id nil
        :action :dropped-write
        :dropped true
        ;; Merged in three pieces rather than written as one literal so that the
        ;; key order is the one this payload has always had — `:body` in the
        ;; middle — and a non-sealing user's row stays byte-identical.
        :payload (merge {:method (some-> (:request-method req) name)
                         :uri (:uri req)}
                        (body-entry ds parent-id req)
                        {:reason (name reason)})}))
    (catch Throwable _ nil)))

(defn wrap-machine-write-guard
  "When a verified token marks the caller as a machine user and the request
  mutates an /api/* endpoint, only let it through while recording mode is on.
  Otherwise log the intent and return a stub response — read access stays
  open regardless. Mail-only machine users never get through to non-mail
  endpoints, even when recording is on."
  [handler]
  (fn [req]
    (if (and (api-request? req) (mutating? req) (not (gate-exempt? req)))
      (if-let [claims (machine-claims req)]
        (do (tel/log! {:level :info
                       :data {:intent :machine-write
                              :uri (:uri req)
                              :method (:request-method req)
                              :machine-user-id (:user-id claims)
                              :for-user-id (:for-user-id claims)
                              :mail-only (boolean (:mail-only claims))
                              :recording (enabled?)}}
                      "MACHINE WRITE")
            (cond
              (and (enabled?) (not (:mail-only claims)))
              (handler req)

              :else
              (do (record-dropped-event! req claims
                                         (if (:mail-only claims) :mail-only :recording-off))
                  {:status 200
                   :headers {"Content-Type" "application/json"}
                   :body "{\"dropped\":true}"})))
        (handler req))
      (handler req))))
