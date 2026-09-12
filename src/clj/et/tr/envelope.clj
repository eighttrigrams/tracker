(ns et.tr.envelope
  "The whole of the server's knowledge of the seal, which is deliberately very
  little: a prefix, an inventory, and one rule about writes.

  **There is no crypto in here and there never will be.** Tracker is served from
  fly and the key is never sent there; the moment it were, the encryption would be
  a decoration with no error message to say so. So the server can tell that a
  value is sealed and cannot tell what it says — which is enough to enforce the
  one thing only it can enforce.

  ## The one thing only the server can enforce

  Tracker has three humans in one SQLite file and only one of them seals. *Whose
  rows are these?* is a question a client cannot answer — a CLI guessing from a
  username is a guess that seals somebody else's prose, and there is no
  recovering from that. The server knows who is asking, so the server holds the
  flag and the server refuses.

  The rule, and the wording is load-bearing:

  > For a user whose `seal_prose` is 1, a write may not **introduce** new
  > plaintext prose into a sealed column. An **echo** of the value already stored
  > is not an introduction.

  | the write | `seal_prose` 1 | `seal_prose` 0 |
  | --- | --- | --- |
  | blank | allow | allow |
  | `enc:v1:…` | allow | **refuse** |
  | plaintext equal to stored | allow — an echo | allow |
  | plaintext differing from stored | **refuse** | allow |
  | plaintext on a create | **refuse** | allow |

  The echo row is what keeps a half-migrated database usable and what stops a
  no-op save being refused. The `seal_prose` 0 ciphertext row is the other
  direction: a client that holds a key must not seal `antonio`'s rows.

  ## What it deliberately does not do

  It does not seal, unseal, validate, or look inside a value. A blank check still
  works — blank is never sealed, so blank still arrives blank. Anything that
  claimed to validate the *content* of a body could not, and does not."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [et.tr.db :as db]
            [et.tr.server.common :as common]
            [et.tr.seal-rules :as rules]))

(def prefix
  "The `enc:v1:` marker. The server's entire vocabulary for the seal, and it is
  pinned to `test/fixtures/seal-vectors.edn` by `et.tr.envelope-test` so it
  cannot drift from the two clients that actually hold keys."
  rules/envelope-prefix)

(def sealed? rules/sealed?)
(def blank-value? rules/blank-value?)
(def sealed-columns rules/sealed-columns)
(def clear-tables rules/clear-tables)
(def sealed-in rules/sealed-in)

(defn sealed-table?
  "Whether this table carries prose that a sealing user's writes must seal."
  [table]
  (contains? sealed-columns table))

;; ---------------------------------------------------------------------------
;; The guard, as one middleware.

(defn- seals?
  "Whether this user's prose is sealed. One small query, and only asked when a
  write actually carries a sealed column — so the overwhelming majority of
  requests never pay for it."
  [ds user-id]
  (= 1 (:seal_prose (jdbc/execute-one! (db/get-conn ds)
                                       (sql/format {:select [:seal_prose]
                                                    :from [:users]
                                                    :where [:= :id user-id]})
                                       db/jdbc-opts))))

(defn- stored-value
  "What that column holds right now, for the echo check. Scoped by `user_id` as
  every read in this app is: a row somebody else owns is not this write's
  business and must not answer for it."
  [ds table column id user-id]
  (when id
    (get (jdbc/execute-one! (db/get-conn ds)
                            (sql/format {:select [column]
                                         :from [table]
                                         :where [:and [:= :id id] [:= :user_id user-id]]})
                            db/jdbc-opts)
         column)))

(defn- refusal-for
  "The refusal this request earns, or `nil`.

  Note the order of the tests, which is also the order of their cost. A sealing
  user's ordinary write carries an envelope, `sealed?` answers yes, and nothing
  is queried at all. The extra read happens only on the path that is **about to
  refuse**, to find out whether the plaintext is an echo of what is already
  there — which is the case a half-migrated database produces on every no-op
  save, and the reason mixed state stays usable.

  **The two message conversions are the one place an *absent* column is a
  refusal**, and they earn the exception by deleting their own original. Every
  other write here can be wrong and then corrected: the row survives, the client
  reads it back, the next save seals it. A convert cannot. It writes prose into a
  sealed column and `DELETE`s the message it came from in the same transaction,
  so the moment it returns 200 there is no clear original to fall back on, no
  stored value to diff against, and nothing to tell anyone it happened. The one
  moment this can be got right is before it runs. A sealing user who sends no
  `description` is therefore told to send one rather than quietly handed a task
  whose body is readable on fly.

  A convert is also the one write here whose table is not its endpoint's, which
  is why `table` is resolved through `convert-target` first."
  [req ds]
  (let [uri (:uri req)
        ;; A message conversion writes into `tasks` or `resources` while sitting
        ;; at a `messages` URL, and it is the one write that deletes its own
        ;; original — see `rules/convert-endpoint->table`. Everywhere else the
        ;; endpoint's own table is the one being written.
        convert (rules/convert-target uri)
        table (or convert (rules/endpoint-table uri))
        body (:body req)
        columns (when (and table (map? body))
                  (filter #(contains? body %) (get sealed-columns table)))
        interesting (seq (remove #(blank-value? (get body %)) columns))]
    (when (or interesting convert)
      (when-let [user-id (:user-id (common/get-user-from-request req))]
        (let [sealing? (seals? ds user-id)
              ;; A convert **creates** its row, so there is nothing stored to
              ;; echo — and the id in its path is the *message's*, which would
              ;; answer for an unrelated task if it were used here.
              id (when-not convert (rules/endpoint-id uri))]
          (or
           (when (and convert sealing? (not (contains? body :description)))
             {:success false
              :error (str "This user's prose is sealed, and a conversion must carry the "
                          "sealed body. Send description with this request: the message "
                          "is deleted by it, so its text cannot be recovered afterwards.")
              :reason "unsealed"
              :column "description"})
           (some (fn [column]
                   (let [v (get body column)]
                     (cond
                       (and sealing? (not (sealed? v)))
                       (when-not (= v (stored-value ds table column id user-id))
                         {:success false
                          :error (str "This user's " (name column) " is sealed. A write "
                                      "may not put readable text into it.")
                          :reason "unsealed"
                          :column (name column)})

                       (and (not sealing?) (sealed? v))
                       {:success false
                        :error (str "This user does not seal prose, and this write carries "
                                    "an envelope in " (name column) ".")
                        :reason "sealed"
                        :column (name column)}

                       :else nil)))
                 interesting)))))))

(defn wrap-seal-guard
  "Refuse, with a 400 and nothing written, any write that would put readable text
  into a sealing user's prose column — or an envelope into a column belonging to
  somebody who does not seal.

  **Placed innermost**, closest to the routes, because it needs the parsed body
  and resolves the caller itself. Everything it knows about which endpoints carry
  prose comes from `et.tr.seal-rules`, the same file the browser and
  `plurama-cli` read — so an endpoint added to one is added to all three, and one
  that is added to none is refused by none, visibly and in one place rather than
  quietly in three.

  It is the server half of *client seals, server enforces*. The client half
  cannot be trusted on its own for the reason the namespace docstring gives:
  which rows are the sealing user's is the one question a client cannot answer."
  [handler]
  (fn [req]
    (if (#{:put :post :patch} (:request-method req))
      (if-let [refusal (refusal-for req (common/ensure-ds))]
        {:status 400 :body refusal}
        (handler req))
      (handler req))))
