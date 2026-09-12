(ns et.tr.ui.api
  "Every HTTP call the tracker UI makes, and the one place the seal meets them.

  ## The asymmetry, which is the design and not an accident

  **Unsealing hangs here, at the transport layer.** A read has nothing to decide:
  the `enc:v1:` prefix says what is sealed, tracker binds all ten tables under one
  name so nothing has to be classified first, and a value that will not open comes
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
  ;; `{[table id column] ciphertext}`. Not a cache of anything readable — it holds
  ;; only values this browser received already sealed, and it is emptied on sign-out.
  (atom {}))

(defn forget-stored!
  "Drop everything remembered about what rows hold. Called on sign-out: the next
  user of this browser is not this one, and their writes must not echo bytes from
  somebody else's rows."
  []
  (reset! stored {}))

(defn- remember!
  "Index what arrived sealed, **before** anything is opened."
  [endpoint body]
  (swap! stored seal/remember endpoint body))

(defn- unsealing
  "Wrap a success handler so it is given an opened body.

  Error bodies are deliberately not unsealed: an error carries a message and a
  reason, not prose, and a rejected promise inside an error path is a way to lose
  the error."
  [endpoint handler]
  (fn [body]
    (remember! endpoint body)
    (-> (seal/unseal-body (key-store/current-key) body)
        (.then (fn [opened] (when handler (handler opened))))
        (.catch (fn [_] (when handler (handler body)))))))

(defn- sealing
  "`et.tr.ui.seal/seal-params`, with this namespace's key and index supplied. The
  decision itself lives there, where a test can reach it."
  [endpoint params]
  (seal/seal-params (key-store/current-key) @stored endpoint params))

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
