(ns et.tr.ui.api
  "Every HTTP call the tracker UI makes, and the one place the seal meets them.

  ## That first sentence is now checked

  It was not true when it was written. Eighteen ClojureScript namespaces referred
  `ajax.core` directly, thirteen of them read a response carrying a `description`
  for a sealed table, and so the read path below never ran on any list page in the
  app. The consequence was not only `enc:v1:…` rendered as a body: because the
  index is fed from in here, those rows were **indexed nowhere**, and an inline
  title edit then sent the ciphertext back through the sealing write path with
  nothing to compare it against. What got written was `enc(enc(…))`, and nothing
  anywhere reported it.

  A docstring is not a control, so there is one beside it:
  `et.tr.api-is-the-only-door-test` fails if any namespace under `src/cljs`
  except this one *requires* `ajax.core`. It is a rule over the source tree
  rather than a behaviour test on purpose — the defect is an *absence*, and no
  assertion about how tracker fetches tasks can fail because somebody added a
  nineteenth namespace that fetches meets its own way.

  It asks the `ns` form for the symbol rather than the file for the string, and
  that distinction was bought: the first namespace to *document* that it reaches
  `ajax.core` through here was flagged by the old grep for saying so.

  There is **no allowlist**, and the endpoints that carry no prose go through
  here too: translations, auth, sources. *This endpoint carries no prose* is a
  judgement that goes stale, and the thirteen call sites above are what it looks
  like when it has — each was written by somebody who had no reason to think
  about a seal, because there was not one yet. A response with nothing sealed in
  it costs one walk and no crypto.

  The one call that is not here is `state/ui.cljs`'s `/api/export`, which is a
  `js/fetch` for a **Blob** — a ZIP, not JSON, so there is nothing for these
  functions to parse and nothing for the seal to open. It is outside for a reason
  that cannot go stale, rather than by a judgement about its contents. The export
  is assembled server-side and is full of `enc:v1:…` under the seal; that is a
  known, accepted gap for this pass, and it is the docs step's to say so.

  ## The asymmetry, which is the design and not an accident

  **Unsealing hangs here, at the transport layer.** A read has nothing to decide:
  the `enc:v1:` prefix says what is sealed, tracker binds all nine body-carrying
  tables under one name so nothing has to be classified first, and a value that will not open comes
  back visibly. Putting it here means a new endpoint cannot silently go unsealed,
  because nothing had to be taught about it.

  **Sealing hangs here too**, which is where tracker and cookbook differ. Cookbook
  has to seal up in its state layer, because a write must know what the column
  holds *right now* and only the state layer knows which row is being written.
  Tracker's routes are regular enough to answer that here — `/api/tasks/123` names
  a table and a row — so the eleven write paths need no change at all, and a
  twelfth nobody has written yet is covered in advance.

  ## The echo rule needs the bytes that are stored, so this remembers them

  Unsealing throws the ciphertext away, and a save may come minutes later. So
  every response is indexed on the way past — `[table id column] → ciphertext` —
  and a write looks its row up. Keyed by row and never by text: two tasks may
  legitimately say the same sentence, and echoing one's ciphertext into the other
  would leak exactly the equality a fresh nonce exists to hide.

  A row that cannot be identified with certainty is not indexed, and the write
  then seals afresh. See `et.tr.seal-rules/stored-entries` for why that asymmetry
  is the safe one.

  ## With no key, all of this is one walk and no crypto

  Which is every response for `antonio` and `saiyuri`, who do not seal at all;
  every response before the cutover; and every response on a browser the key has
  not been pasted into."
  (:require [ajax.core :refer [GET POST PUT DELETE]]
            [et.tr.ui.key-store :as key-store]
            [et.tr.ui.seal :as seal]))

(defonce ^:private stored
  ;; `{[table id column] value}` — what each row's body held when this browser
  ;; last read it, in whichever encoding it arrived in. A ciphertext from a
  ;; migrated row; a plaintext from one the walker has not reached, because rule
  ;; 2 echoes either. Nothing here that the app-state does not already hold in
  ;; the clear, and it is emptied on sign-out.
  (atom {}))

(defn forget-stored!
  "Drop everything remembered about what rows hold. Called on sign-out: the next
  user of this browser is not this one, and their writes must not echo bytes from
  somebody else's rows."
  []
  (reset! stored {}))

(defn- remember!
  "Index what arrived, **before** anything is opened — which is the ordering the
  ciphertext half depends on, since unsealing is what throws the bytes away."
  [endpoint body]
  (swap! stored seal/remember endpoint body))

(defn- unsealing
  "Wrap a success handler so it is given an opened body, **once**.

  The index first, the opening second — that ordering is load-bearing, because
  unsealing is what throws the ciphertext away. *Exactly once* is
  `seal/opening`'s, where a test can count the calls; this is only the key and
  the index.

  Error bodies are deliberately not unsealed: an error carries a message and a
  reason, not prose, and a rejected promise inside an error path is a way to lose
  the error."
  [endpoint handler]
  (fn [body]
    (remember! endpoint body)
    (seal/opening (key-store/current-key) body handler)))

(defn- sealing
  "`et.tr.ui.seal/seal-params`, with this namespace's key and index supplied. The
  decision itself lives there, where a test can reach it.

  **`sealing-key` and not `current-key`**, and that asymmetry is the whole of
  F-1. A key in this browser does not mean the user signed in is entitled to seal
  with it: the key survives sign-out on purpose, and survives `--disarm`, while
  the flag changes in the database out of band. `unsealing` above keeps the
  ungated key, because reading sealed rows is exactly what a disarmed user must
  go on doing until `--unseal` has run."
  [endpoint params]
  (seal/seal-params (key-store/sealing-key) @stored endpoint params))

(defn fetch-json
  [endpoint headers handler]
  (GET endpoint
    {:response-format :json
     :keywords? true
     :headers headers
     :handler (unsealing endpoint handler)}))

(defn fetch-json-with-error
  [endpoint headers handler error-handler]
  (GET endpoint
    {:response-format :json
     :keywords? true
     :headers headers
     :handler (unsealing endpoint handler)
     :error-handler error-handler}))

(defn post-json
  ([endpoint params headers handler]
   (post-json endpoint params headers handler nil))
  ([endpoint params headers handler error-handler]
   (.then (sealing endpoint params)
          (fn [params]
            (POST endpoint
              (cond-> {:params params
                       :format :json
                       :response-format :json
                       :keywords? true
                       :headers headers
                       :handler (unsealing endpoint handler)}
                error-handler (assoc :error-handler error-handler)))))))

(defn put-json
  ([endpoint params headers handler]
   (put-json endpoint params headers handler nil))
  ([endpoint params headers handler error-handler]
   (.then (sealing endpoint params)
          (fn [params]
            (PUT endpoint
              (cond-> {:params params
                       :format :json
                       :response-format :json
                       :keywords? true
                       :headers headers
                       :handler (unsealing endpoint handler)}
                error-handler (assoc :error-handler error-handler)))))))

(defn delete-json
  ([endpoint params headers handler]
   (delete-json endpoint params headers handler nil))
  ([endpoint params headers handler error-handler]
   (DELETE endpoint
     (cond-> {:params params
              :format :json
              :response-format :json
              :keywords? true
              :headers headers
              :handler (unsealing endpoint handler)}
       error-handler (assoc :error-handler error-handler)))))

(defn delete-simple
  ([endpoint headers handler]
   (delete-simple endpoint headers handler nil))
  ([endpoint headers handler error-handler]
   (DELETE endpoint
     (cond-> {:format :json
              :response-format :json
              :keywords? true
              :headers headers
              :handler (unsealing endpoint handler)}
       error-handler (assoc :error-handler error-handler)))))
