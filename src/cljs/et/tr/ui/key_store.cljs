(ns et.tr.ui.key-store
  "Where the browser keeps the tracker key.

  The key is not the same kind of thing as the login. The JWT says **who you
  are**; the key says **who may read prose**. Tracker is served from fly and the
  key is never sent there, so the browser holds its own copy, and how it holds it
  is the whole difference between end-to-end encryption and a decoration.

  ## Non-extractable, in IndexedDB

  The key is imported once as a `CryptoKey` with `extractable` **false** and
  stored in IndexedDB. Two properties follow, and both are the point:

  - **The page can use it and cannot read it.** `crypto.subtle.decrypt` works;
    `exportKey` throws. A cross-site scripting bug on this page could seal and
    unseal while the tab is open — nothing prevents that and nothing could — but
    it cannot take the key away with it.
  - **It survives a reload** without the key material ever having been a string
    the app kept.

  IndexedDB is what makes both true at once: it is the only browser store that
  holds a live `CryptoKey` object rather than text. Note the comparison close to
  hand — tracker's own JWT sits in `localStorage` (`et.tr.ui.state.auth`), and a
  base64 key there would be one `JSON.stringify` from being posted somewhere.
  That is the standard the key has to beat, and this is how it beats it.

  ## Getting it in

  Paste it into the ⚙ panel, once per browser. On a phone the same panel takes
  the same base64, by QR or by typing. There is deliberately **no** import path
  through a URL fragment, a query parameter or a message from an agent: an agent
  that could hand a key to a browser is one more way to lose one.

  ## No key is a legitimate state, and here there are four of them

  Cookbook had three. Tracker has a fourth, and it is the ordinary one: **the
  other people using this tracker are not sealing at all.** `antonio` and
  `saiyuri` have no key, should never have one, and nothing about their rows is
  sealed — so for them this namespace simply stays `:absent` forever and the app
  is exactly what it was.

  The other three: the owner on a browser he has not pasted the key into yet, a
  database nobody has migrated, and a borrowed machine where he has pressed
  Forget. None of them is an error, and nothing here blocks the app on a key."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.tr.ui.seal :as seal]))

(def ^:private db-name "tracker")
(def ^:private store-name "seal-key")
(def ^:private record-key "current")
(def ^:private key-bytes 32)

(defonce state
  ;; `{:status :unknown|:absent|:present, :key CryptoKey|nil, :fingerprint str|nil}`.
  ;;
  ;; A reagent atom because the ⚙ panel renders off it. **The `CryptoKey` in here is
  ;; not a secret the page can spill** — it is non-extractable, so what is held is a
  ;; handle rather than key material.
  (r/atom {:status :unknown :key nil :fingerprint nil}))

(defn current-key
  "The key, or `nil` when there is none — which every function in `et.tr.ui.seal`
  takes to mean *sealing is off*."
  []
  (:key @state))

(defn- open-db []
  (js/Promise.
   (fn [resolve reject]
     (let [req (.open js/indexedDB db-name 1)]
       (set! (.-onupgradeneeded req)
             (fn [_]
               (let [db (.-result req)]
                 (when-not (.contains (.-objectStoreNames db) store-name)
                   (.createObjectStore db store-name)))))
       (set! (.-onsuccess req) (fn [_] (resolve (.-result req))))
       (set! (.-onerror req) (fn [_] (reject (.-error req))))))))

(defn- tx-request
  "One IndexedDB request, as a promise. `f` is handed the object store and returns
  the request to wait on."
  [mode f]
  (.then (open-db)
         (fn [db]
           (js/Promise.
            (fn [resolve reject]
              (let [store (.objectStore (.transaction db #js [store-name] mode) store-name)
                    req (f store)]
                (set! (.-onsuccess req) (fn [_] (resolve (.-result req))))
                (set! (.-onerror req) (fn [_] (reject (.-error req))))))))))

(defn load!
  "Read the key back out of IndexedDB into `state`.

  **Called once, before the app's first request goes out.** A fetch that raced
  this would hand its handler ciphertext and put it in the app-state, where the
  next render would show `enc:v1:…` on a page that does hold the key — the
  confusing failure rather than the honest one.

  Any failure lands on `:absent`. Private browsing, a blocked IndexedDB, a first
  visit, a user who does not seal: all of them mean *this browser has no key*,
  which is a legitimate state and not an error to put in front of anybody."
  []
  (-> (tx-request "readonly" #(.get % record-key))
      (.then (fn [record]
               (if record
                 (reset! state {:status :present
                                :key (.-key record)
                                :fingerprint (.-fingerprint record)})
                 (reset! state {:status :absent :key nil :fingerprint nil}))))
      (.catch (fn [_] (reset! state {:status :absent :key nil :fingerprint nil})))))

(defn import-key!
  "The panel's action: validate, fingerprint, import non-extractably, store, and
  swap it in. Resolves to the fingerprint, which is what the panel shows and what
  gets compared — by eye, against the walker's header and the proxy's startup line
  — before the one irreversible step in this whole design.

  The pasted text is never written anywhere but the `CryptoKey` this produces; the
  caller clears its own input."
  [text]
  (let [raw (try (let [bin (js/atob (str/trim text))
                       out (js/Uint8Array. (.-length bin))]
                   (dotimes [i (.-length bin)] (aset out i (.charCodeAt bin i)))
                   out)
                 (catch :default _ nil))]
    (cond
      (nil? raw)
      (js/Promise.reject (js/Error. "That is not base64."))

      (not= key-bytes (.-length raw))
      (js/Promise.reject
       (js/Error. (str "A tracker key is " key-bytes " bytes; that one is "
                       (.-length raw) ". (Cookbook's key is a different key —"
                       " check you have not pasted that one.)")))

      :else
      (-> (js/Promise.all #js [(seal/import-key raw) (seal/fingerprint raw)])
          (.then (fn [[k fp]]
                   (.then (tx-request "readwrite"
                                      #(.put % #js {:key k :fingerprint fp} record-key))
                          (fn [_]
                            (reset! state {:status :present :key k :fingerprint fp})
                            fp))))))))

(defn forget!
  "Drop the key from this browser. It is not a delete of anything — the key lives
  in `secrets.yaml` and on paper, and the data stays sealed — it is this device
  forgetting. Useful on a borrowed machine, and useful for seeing tracker as
  somebody without the key sees it."
  []
  (-> (tx-request "readwrite" #(.delete % record-key))
      (.then (fn [_] (reset! state {:status :absent :key nil :fingerprint nil})))
      (.catch (fn [_] (reset! state {:status :absent :key nil :fingerprint nil})))))
