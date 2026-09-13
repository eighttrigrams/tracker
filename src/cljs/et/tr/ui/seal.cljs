(ns et.tr.ui.seal
  "The browser's half of tracker's seal: WebCrypto, and the three rules over it.

  What is *not* here is anything about tracker's schema. The prefix, what counts
  as blank, what a value is bound to, which ten columns are sealed and where
  prose hides inside an audit payload all live in `et.tr.seal-rules`, a `.cljc`
  file this namespace, tracker's server and `plurama-cli/tracker_seal.clj` all
  read. Only the cipher is written twice, because only the cipher genuinely
  differs: WebCrypto is asynchronous and `javax.crypto` is not.

  **Everything here returns a Promise.** That is not an implementation detail
  leaking — it is the reason the split exists. `crypto.subtle` has no synchronous
  door, and the alternative (a pure-JavaScript AES-GCM) would mean the key is a
  byte array living in JavaScript rather than a **non-extractable `CryptoKey`**,
  which is the single property this whole arrangement is built on. The page may
  use the key. The page may not read it.

  ## Where the key is

  `et.tr.ui.key-store` — an IndexedDB record holding a live `CryptoKey` object.
  Not `localStorage`, which is where tracker's JWT already sits and is precisely
  the standard the key has to beat: a base64 string in `localStorage` is one
  `JSON.stringify` away from being posted somewhere.

  ## Sealing is off when there is no key

  Every function takes the key first and treats `nil` as *sealing off*: values
  pass through unchanged. Three legitimate keyless states — a second user of this
  tracker who is not sealing at all, the owner on a browser he has not pasted the
  key into yet, and an unmigrated database — and none of them is an error.

  The drift control is `test/fixtures/seal-vectors.edn` and
  `test/cljs/et/tr/ui/seal_test.cljs`. Run it with `make test-cljs`; `make test`
  alone says nothing about the envelope."
  (:require [clojure.string :as str]
            [et.tr.seal-rules :as rules]))

;; ---------------------------------------------------------------------------
;; The rules that are not the cipher, re-exported so no caller reaches past here.

(def envelope-prefix rules/envelope-prefix)
(def sealed? rules/sealed?)
(def blank-value? rules/blank-value?)
(def bound-as rules/bound-as)
(def aad rules/aad)
(def item-description-aad rules/item-description-aad)
(def event-body-aad rules/event-body-aad)
(def sealed-columns rules/sealed-columns)
(def sealed-in rules/sealed-in)
(def prose-paths rules/prose-paths)
(def body-prose-paths rules/body-prose-paths)
(def stored-entries rules/stored-entries)
(def endpoint-table rules/endpoint-table)
(def endpoint-id rules/endpoint-id)
(def convert-target rules/convert-target)

;; ---------------------------------------------------------------------------
;; The envelope.

(def ^:private nonce-length 12)   ; 96 bits, the GCM standard
(def ^:private tag-bits 128)
(def ^:private key-length 32)     ; AES-256

(defn- subtle [] (.-subtle js/crypto))

(defn- utf8 [s] (.encode (js/TextEncoder.) s))
(defn- from-utf8 [buf] (.decode (js/TextDecoder. "utf-8") buf))

(defn random-bytes [n]
  (.getRandomValues js/crypto (js/Uint8Array. n)))

(defn- bytes->b64
  "Chunked, because `String.fromCharCode` is variadic and a whole tracker body
  applied as one argument list overflows the stack. 0x8000 is the chunk cookbook
  settled on and there is no reason to differ."
  [u8]
  (let [n (.-length u8)]
    (loop [i 0 acc ""]
      (if (< i n)
        (recur (+ i 0x8000)
               (str acc (.apply js/String.fromCharCode nil (.subarray u8 i (min n (+ i 0x8000))))))
        (js/btoa acc)))))

(defn- b64->bytes [s]
  (let [bin (js/atob s)
        out (js/Uint8Array. (.-length bin))]
    (dotimes [i (.-length bin)]
      (aset out i (.charCodeAt bin i)))
    out))

(defn- resolved [v] (js/Promise.resolve v))

(defn- gcm-params [nonce aad-str]
  #js {:name "AES-GCM" :iv nonce :additionalData (utf8 aad-str) :tagLength tag-bits})

(defn import-key
  "A raw 32-byte key as a **non-extractable** `CryptoKey`.

  The `false` is the whole point: the page can encrypt and decrypt with it and
  cannot read the bytes back out. That is what makes IndexedDB the right place to
  keep it and `localStorage` the wrong one, and it is the one property that
  survives a cross-site script getting a foothold on the page."
  [raw]
  (when-not (= key-length (.-length raw))
    (throw (ex-info (str "a tracker key is " key-length " bytes, got " (.-length raw))
                    {:length (.-length raw)})))
  (.importKey (subtle) "raw" raw #js {:name "AES-GCM"} false #js ["encrypt" "decrypt"]))

(defn generate-key-base64
  "A fresh key, base64. For tests, and for the one time a real one is minted —
  which is not a thing this page does on its own."
  []
  (bytes->b64 (random-bytes key-length)))

(defn fingerprint
  "Eight hex characters of SHA-256 over the raw key, computed over the **raw
  bytes** rather than the imported key — a non-extractable `CryptoKey` cannot be
  read back, which is the point of it, so this is asked at import time and the
  answer is what the ⚙ panel remembers.

  It is what makes *this browser holds the key the walker is about to use* a
  five-second comparison. Sealing a database with a key the browser cannot open
  is the one mistake in this design with no recovery."
  [raw]
  (.then (.digest (subtle) "SHA-256" raw)
         (fn [digest]
           (->> (array-seq (js/Uint8Array. digest))
                (take 4)
                (map #(.padStart (.toString % 16) 2 "0"))
                (str/join)))))

(defn seal-text-with-nonce
  "**The fixture's arity, and nothing else's** — which is why it has a name you
  have to type rather than an overload you can fall into.

  A nonce supplied by a caller is a nonce that can be supplied twice, and in GCM
  two values under one key and one nonce is not a weakening, it is a total break.
  Nothing in this application has any reason to choose one."
  [k aad-str plaintext nonce]
  (.then (.encrypt (subtle) (gcm-params nonce aad-str) k (utf8 plaintext))
         (fn [ct]
           (let [ct (js/Uint8Array. ct)
                 out (js/Uint8Array. (+ (.-length nonce) (.-length ct)))]
             (.set out nonce 0)
             (.set out ct (.-length nonce))
             (str envelope-prefix (bytes->b64 out))))))

(defn seal-text
  "The envelope itself: plaintext in, `enc:v1:…` out. A fresh 96-bit nonce per
  value, every time. No rules and no inventory — `seal` below is what call sites
  use."
  [k aad-str plaintext]
  (seal-text-with-nonce k aad-str plaintext (random-bytes nonce-length)))

(defn unseal-text
  "The inverse, for a value known to carry the prefix. Rejects when the tag does
  not check out.

  **The `try` is load-bearing and was a real bug.** `js/atob` throws
  *synchronously*, before any promise exists, so without this a `.catch` installed
  downstream never fires and the throw escapes into the ajax handler — taking a
  whole response down and saying nothing about why. A body that happens to begin
  with `enc:v1:` is all it takes."
  [k aad-str value]
  (try
    (let [raw (b64->bytes (subs value (count envelope-prefix)))
          nonce (.slice raw 0 nonce-length)
          body (.slice raw nonce-length)]
      (.then (.decrypt (subtle) (gcm-params nonce aad-str) k body) from-utf8))
    (catch :default e (js/Promise.reject e))))

;; ---------------------------------------------------------------------------
;; The three rules. Argued at length in `plurama-cli/seal_envelope.clj`; the two
;; must agree, and the fixture is what says so.

(defn unseal-at
  "Read one value, given what it is bound to. Prefix-driven: anything without
  `enc:v1:` comes back as it went in, and so does everything when there is no key.

  **A value that will not open comes back as it is**, visibly, rather than
  throwing. One unreadable body beside everything that reads beats a whole
  response dropped with nothing to say why."
  [k aad-str v]
  (if (and k (sealed? v))
    (.catch (unseal-text k aad-str v) (fn [_] v))
    (resolved v)))

(defn seal-at
  "Write one value, given what it is bound to, under all three rules.

  `stored` is what that column holds right now. When the value has not changed,
  `stored` comes back byte for byte, whichever encoding it is in — see
  `seal-envelope/seal-at`, where the two halves of that comparison are argued.
  The order matters: **the bytes first, before anything is opened.**

  **An envelope is handed straight back, whatever is stored.** Both halves of the
  echo rule need something to compare against, and when `stored` is `nil` — a
  create, a row this browser has not read, an index emptied at sign-out — neither
  can answer. A value that already carries the prefix then fell through to
  `seal-text` and was sealed a second time. A page only ever holds an envelope
  because it read one, so handing it back is what *unchanged* means here; sealing
  it writes `enc(enc(…))`, which opens once into an envelope and reads as one,
  with nothing reporting an error."
  ([k aad-str v] (seal-at k aad-str v nil))
  ([k aad-str v stored]
   (cond
     (nil? k) (resolved v)
     (blank-value? v) (resolved v)
     (sealed? v) (resolved v)
     (= v stored) (resolved stored)
     :else (.then (unseal-at k aad-str stored)
                  (fn [was] (if (= was v) stored (seal-text k aad-str v)))))))

(defn unseal
  "Read one value out of one column."
  [k table column v]
  (unseal-at k (aad table column) v))

(defn seal
  "Write one value into one column, under all three rules. **A migration pass
  must pass `nil` as `stored`** — but there is no migration pass in a browser, so
  what this arity is really for is a row whose current value this client has read
  and remembers."
  ([k table column v] (seal k table column v nil))
  ([k table column v stored]
   (seal-at k (aad table column) v stored)))

;; ---------------------------------------------------------------------------
;; Walking a structure, one path at a time.
;;
;; Rows and audit payloads are the same problem — a handful of places in a map
;; that need an asynchronous function applied to them — so they get one walker.
;; `et.tr.seal-rules/prose-paths` supplies the payload paths; `row-paths` here
;; supplies a row's.

(defn- walk-paths
  "Apply `f` — which takes the path, the binding and the value, and answers a
  Promise — at each `[path aad]`, in sequence, resolving to the updated map.

  Sequential rather than `Promise.all` because the accumulator is the map itself
  and the paths are few: a row has one, the widest payload shape has two."
  [m pairs f]
  (reduce (fn [pm [path aad-str]]
            (.then pm (fn [acc]
                        (.then (f path aad-str (get-in acc path))
                               (fn [v] (assoc-in acc path v))))))
          (resolved m)
          pairs))

(defn- row-paths
  "The sealed columns this row actually carries, as `[path aad]`.

  A column the row does not have yields nothing, and that is deliberate: tracker's
  machine listings are lean on purpose (`middleware/machine_lean.clj` strips
  `:description`) and an unseal must not invent an empty body on a listing that
  deliberately has none."
  [table row]
  (for [column (get sealed-columns table)
        :when (contains? row column)]
    [[column] (aad table column)]))

(defn unseal-row
  "Unseal every sealed column of one row of `table`, leaving every other key
  alone."
  [k table row]
  (if (or (nil? k) (nil? row))
    (resolved row)
    (walk-paths row (row-paths table row) (fn [_ aad-str v] (unseal-at k aad-str v)))))

(defn seal-row
  "Seal every sealed column present in `row`, echoing `stored`'s value for
  anything that has not changed. `stored` is the row as this client last read it."
  ([k table row] (seal-row k table row nil))
  ([k table row stored]
   (if (or (nil? k) (nil? row))
     (resolved row)
     (walk-paths row (row-paths table row)
                 (fn [path aad-str v]
                   (seal-at k aad-str v (get-in stored path)))))))

(defn unseal-payload
  "Open every sealed value inside one already-parsed event payload. The shapes
  are `et.tr.seal-rules/prose-paths`'s knowledge, not this file's."
  [k payload]
  (if (nil? k)
    (resolved payload)
    (walk-paths payload (prose-paths payload) (fn [_ aad-str v] (unseal-at k aad-str v)))))

(defn unseal-body
  "Open every sealed value anywhere in one response body.

  This is the whole read path. It hangs at the transport layer — `et.tr.ui.api`
  wraps every handler with it — because **a read has nothing to decide**: the
  prefix says what is sealed, one binding covers every table, and a value that
  will not open comes back visibly. Sealing cannot live here, because a write has
  to know what the column holds right now.

  A body with nothing sealed in it is returned as it is, having cost one walk and
  no crypto — which is every response before the cutover, every response for a
  user who is not sealing, and every response at all when this browser holds no
  key."
  [k body]
  (if (nil? k)
    (resolved body)
    (let [paths (body-prose-paths body)]
      (if (empty? paths)
        (resolved body)
        (walk-paths body paths (fn [_ aad-str v] (unseal-at k aad-str v)))))))

(defn opening
  "Hand `handler` the body with its prose opened — and **exactly once**,
  whichever way it goes.

  The order of the two callbacks is the whole of this function. It was written
  the other way round, inside `et.tr.ui.api`:

      (-> (unseal-body k body)
          (.then  (fn [opened] (handler opened)))
          (.catch (fn [_]      (handler body))))

  and the `.catch` there is installed on the promise `.then` *returns*, so it
  catches a rejection from `unseal-body` **and any throw from `handler`
  itself**. A handler that throws part-way — a bad `swap!`, a shape some
  calculation did not expect — was therefore called a second time with the
  *unopened* body, writing `enc:v1:…` into the app-state on top of a half-applied
  first run, silently. `.catch` first catches only what it is for.

  What it is for is invariant 4: a value that will not open comes back visibly,
  once, beside everything that reads, rather than a whole response dropped with
  nothing to say why.

  **A throw from the handler now comes out** in the returned Promise instead of
  being absorbed by a second call. That is the point — a rejection a console
  shows is strictly better than a silent corruption of the app-state — and it is
  the behaviour change worth knowing about.

  This lives here rather than in `et.tr.ui.api` for the reason the section below
  gives: `api.cljs` cannot be loaded by the node suite at all, because
  `ajax.core` wants `xmlhttprequest`, and *exactly once* is not a claim worth
  making without a test that counts. B-1 widened this wrapper from five call
  sites to about forty-five, which is what made the ordering worth moving."
  [k body handler]
  (-> (unseal-body k body)
      (.catch (fn [_] body))
      (.then (fn [b] (when handler (handler b))))))

;; ---------------------------------------------------------------------------
;; The write path's two halves, kept out of `et.tr.ui.api` so they can be driven
;; by a test rather than by IndexedDB and a network.
;;
;; Cookbook's own review found both of its blocking bugs here rather than in the
;; envelope: a browser cache that never handed the echo rule its input, and two
;; clients taking different branches on one value. The envelope was pinned by a
;; fixture and the wiring was not. So: index → lookup → write is a test.

(defn remember
  "Fold what one response says its rows hold into the stored index. Called
  **before** anything is opened, because unsealing is what throws the ciphertext
  away.

  Both encodings are remembered — a ciphertext from a migrated row, a plaintext
  from one the walker has not reached — because rule 2 echoes *whichever encoding
  the column has*. The value says which it is; the prefix is self-describing.
  Blanks are not remembered: there is nothing to echo, and `seal-at` answers on
  the blank rule before this index is consulted.

  Keyed `[table id column]`, one key per row and column, and a row whose table
  cannot be named with certainty is not indexed at all — see
  `et.tr.seal-rules/stored-entries`, which argues both."
  [index endpoint body]
  (into index
        (for [[table id column ciphertext] (stored-entries endpoint body)]
          [[table id column] ciphertext])))

(defn stored-for
  "What `index` says this endpoint's row holds in that column right now —
  ciphertext or plaintext, whichever the last read of it carried — or `nil`,
  which is what a create is, and what a row this browser has never read is, and
  in both cases the right answer is to seal afresh."
  [index endpoint column]
  (get index [(endpoint-table endpoint) (endpoint-id endpoint) column]))

(defn offers-the-key-box?
  "Whether the ⚙ key panel is for this user.

  **The question is whether they seal, and nothing else.** It was `is_admin` —
  *offer it to everybody who is not the admin* — which is a question about roles,
  asked where a question about sealing belongs. Tracker has three humans in one
  database and one of them seals, so the other two were offered a box that could
  do them no good and some harm: a key in a browser whose rows are all plaintext
  seals nothing, and the first write that carries an envelope into an unsealed
  user's column is refused by the server with a message about a feature they have
  never heard of.

  The answer comes from `GET /api/auth/me`, which resolves it through
  `envelope/seals?` — the guard's own predicate, from the effective user id. The
  client gating this panel and the server refusing writes must not be able to
  disagree, and they cannot when only one of them decides.

  **Absent is a no.** A `:current-user` that has not been refreshed yet, or a
  user record from some other endpoint that does not carry the flag, has not said
  *yes* — and which rows are the sealing user's is precisely the question this
  namespace's docstring says a client may not guess at."
  [current-user]
  (boolean (:seal_prose current-user)))

(defn convert-params
  "The params a message conversion has to send, given the messages the Inbox is
  holding — `{:description <the body of message-id>}` merged into whatever the
  request already carried.

  A convert used to send nothing at all: the server read the message and copied
  its body into the new task or resource. For a sealing user that copy is
  readable prose in a sealed column, and the message it came from is `DELETE`d in
  the same transaction — so the body has to come from the client, which is the
  only side that holds a key. This says *which* text; `seal-params` seals it on
  the way out, which is why nothing here touches crypto and why this is the half
  a test can drive.

  **A message this page does not hold contributes no key at all**, rather than a
  blank. The two are different answers and the difference is the whole point: a
  blank converts cleanly and loses the body permanently, with nothing anywhere to
  say it happened, while a missing key earns the server's refusal, which says
  exactly that and leaves the message in the inbox. The dropdown this is reached
  from is rendered out of the list, so the message is always in hand — and
  *always* is the kind of claim that decides which way a fallback should point,
  not one that makes the fallback unnecessary.

  `\"\"` for a message whose body is genuinely absent, because blank is a value
  here: the link-only message from the feed worker is the commonest convert in
  the app, `nil` would serialise to a JSON `null`, and the server reads a `null`
  as *no body was sent*."
  [messages message-id params]
  (if-let [message (first (filter #(= message-id (:id %)) messages))]
    (assoc params :description (or (:description message) ""))
    params))

(defn seal-params
  "A Promise of `params` with its prose sealed, if this endpoint carries any.

  A `nil` table means an endpoint with no sealed body — `/api/messages` above all
  — and a `nil` key means sealing is off. Either way the params go out as they
  came in, which is the behaviour tracker had before any of this existed.

  **A message conversion is the one write whose table is not its endpoint's**, so
  it is the one place two questions have to be asked instead of one.
  `/api/messages/3/convert-to-task` writes into `tasks`, which is sealed;
  `endpoint-table` answers `nil` for it, correctly, because the rows that
  endpoint *serves* are messages and a message body is never sealed. Ask only
  that one and the browser sends prose, the server refuses it, and the Inbox
  convert is lost for the one user this whole feature is for.

  The same distinction decides `stored`, which is why the two cannot be collapsed
  into a single lookup. The id in that path is the **message's**. Looking
  `[:tasks 3 :description]` up in the index would find some unrelated task's
  ciphertext and echo it into the new row — a valid envelope opening to the wrong
  prose, with nothing reporting an error. A convert is a create, and a create has
  nothing to echo."
  [k index endpoint params]
  (let [convert (convert-target endpoint)
        table (or convert (endpoint-table endpoint))]
    (if (and k table (map? params) (contains? params :description))
      (.then (seal k table :description (:description params)
                   (when-not convert (stored-for index endpoint :description)))
             (fn [v] (assoc params :description v)))
      (resolved params))))
