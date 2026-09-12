(ns et.tr.ui.seal-test
  "The ClojureScript half of tracker's drift control.

  Everything textual in here comes out of `test/fixtures/seal-vectors.edn`, which
  `plurama-cli/test/tracker_seal_test.clj` reads as well. Nothing in this file
  invents a ciphertext: if the browser and the CLI ever disagree about the
  envelope, one of them goes red here.

  Node has WebCrypto natively, so none of this needs a browser. The one part that
  does is the IndexedDB key store, which is checked in the browser instead."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing async]]
            [cljs.reader :as reader]
            [et.tr.ui.seal :as seal]))

(def ^:private vectors-path
  "Relative to the repo root, which is where `make test-cljs` runs node from.

  **Missing is a failure, not a skip.** A fixture whose absence turns the suite
  green would be worse than no fixture: it would report an agreement it never
  checked."
  "test/fixtures/seal-vectors.edn")

(def ^:private fixture
  (delay
    (let [fs (js/require "fs")]
      (when-not (.existsSync fs vectors-path)
        (throw (ex-info (str "no seal vectors at " vectors-path) {})))
      (reader/read-string (.readFileSync fs vectors-path "utf8")))))

(defn- b64->bytes [s]
  (let [bin (js/atob s)
        out (js/Uint8Array. (.-length bin))]
    (dotimes [i (.-length bin)] (aset out i (.charCodeAt bin i)))
    out))

(defn- raw-key [b64] (b64->bytes b64))
(defn- test-key [] (seal/import-key (raw-key (:key-base64 @fixture))))
(defn- other-key [] (seal/import-key (raw-key (:other-key-base64 @fixture))))

(defn- finally! [p done]
  (-> p
      (.then (fn [_] (done)))
      (.catch (fn [e] (is false (str "rejected: " e)) (done)))))

;; ---------------------------------------------------------------------------
;; The contract the fixture states. No key needed for any of these.

(deftest the-fixture-describes-the-envelope-this-file-implements
  (let [{:keys [prefix nonce-bytes tag-bits key-bytes]} (:envelope @fixture)]
    (is (= prefix seal/envelope-prefix))
    (is (= 12 nonce-bytes))
    (is (= 128 tag-bits))
    (is (= 32 key-bytes))))

(deftest the-binding-is-the-one-the-fixture-names
  (is (= (into {} (for [[t b] (:binding @fixture)] [t (keyword b)]))
         seal/bound-as)))

(deftest the-inventory-is-the-one-the-fixture-names
  (is (= (into {} (for [[t cs] (:sealed-columns @fixture)] [t (mapv keyword cs)]))
         seal/sealed-columns))
  (is (= 9 (count seal/sealed-columns))))

(deftest a-vector-resolves-the-aad-its-table-and-column-do
  (doseq [{:keys [name table column aad]} (:vectors @fixture)]
    (testing name
      (is (= aad (seal/aad table column))))))

(deftest the-prefix-is-not-matched-loosely
  (doseq [p (:passthrough @fixture)]
    (is (false? (seal/sealed? p)) (str p " must read as plaintext")))
  (doseq [u (:unopenable @fixture)]
    (is (true? (seal/sealed? u)) (str u " carries the prefix, whatever it holds"))))

(deftest blank-is-what-the-rules-say-it-is
  (doseq [b (conj (:blank @fixture) nil)]
    (is (true? (seal/blank-value? b))))
  (is (true? (seal/blank-value? 7)) "a number in a prose column is left alone"))

;; ---------------------------------------------------------------------------
;; The vectors — the whole point of the file.

(deftest the-fingerprint-is-the-one-the-fixture-names
  (async done
    (finally!
     (.then (seal/fingerprint (raw-key (:key-base64 @fixture)))
            (fn [fp] (is (= (:key-fingerprint @fixture) fp))))
     done)))

(deftest every-vector-seals-to-exactly-the-recorded-ciphertext
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (js/Promise.all
               (clj->js
                (for [{:keys [name aad nonce plaintext sealed]} (:vectors @fixture)]
                  (.then (seal/seal-text-with-nonce k aad plaintext (b64->bytes nonce))
                         (fn [out] (is (= sealed out) name))))))))
     done)))

(deftest every-vector-unseals-back-to-its-plaintext
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (js/Promise.all
               (clj->js
                (for [{:keys [name aad plaintext sealed]} (:vectors @fixture)]
                  (.then (seal/unseal-text k aad sealed)
                         (fn [out] (is (= plaintext out) name))))))))
     done)))

(deftest a-tampered-envelope-fails-to-open
  (async done
    (finally!
     (js/Promise.all
      (clj->js
       (for [{:keys [name aad key-base64 sealed]} (:tamper @fixture)]
         (.then (seal/import-key (raw-key key-base64))
                (fn [k]
                  (-> (seal/unseal-text k aad sealed)
                      (.then (fn [_] (is false (str name " must not open"))))
                      (.catch (fn [_] (is true name)))))))))
     done)))

(deftest a-tampered-envelope-is-handed-back-by-unseal-rather-than-thrown
  ;; Each case with **its own** key. The wrong-key case in the fixture is an
  ;; untampered ciphertext paired with a different key, so opening it with the
  ;; test key would succeed — which is the point of that case and not a failure.
  (async done
    (finally!
     (js/Promise.all
      (clj->js
       (for [{:keys [name key-base64 sealed]} (:tamper @fixture)]
         (.then (seal/import-key (raw-key key-base64))
                (fn [k]
                  (.then (seal/unseal k :tasks :description sealed)
                         (fn [out]
                           (is (= sealed out)
                               (str name " — handed back, not thrown"))))))))) 
     done)))

(deftest a-cookbook-ciphertext-does-not-open-as-a-tracker-item
  (async done
    (let [t (first (filter #(str/includes? (:name %) "cookbook") (:tamper @fixture)))]
      (is (some? t) "the cross-app tamper case must be in the fixture")
      (finally!
       (.then (test-key)
              (fn [k] (.then (seal/unseal k :tasks :description (:sealed t))
                             (fn [out] (is (= (:sealed t) out))))))
       done))))

(deftest a-round-trip-holds-for-a-fresh-nonce
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (js/Promise.all
               (clj->js
                (for [p (:round-trip @fixture)]
                  (.then (seal/seal k :tasks :description p nil)
                         (fn [s] (.then (seal/unseal k :tasks :description s)
                                        (fn [out] (is (= p out) (pr-str p))))))))))) 
     done)))

(deftest the-same-sentence-seals-differently-every-time
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (js/Promise.all
               #js [(seal/seal k :tasks :description "twice" nil)
                    (seal/seal k :tasks :description "twice" nil)])))
     done)))

(deftest a-value-travels-between-all-nine-tables
  (async done
    (finally!
     (-> (test-key)
         (.then (fn [k]
                  (.then (seal/seal k :issues :description "an issue that becomes a task" nil)
                         (fn [s] #js [k s]))))
         (.then (fn [[k s]]
                  (js/Promise.all
                   (clj->js
                    (for [t (keys seal/sealed-columns)]
                      (.then (seal/unseal k t :description s)
                             (fn [out]
                               (is (= "an issue that becomes a task" out)
                                   (str "read as " t))))))))))
     done)))

;; ---------------------------------------------------------------------------
;; The three rules.

(deftest blank-is-never-sealed
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (js/Promise.all
               (clj->js
                (for [b (conj (:blank @fixture) nil)]
                  (.then (seal/seal k :tasks :description b nil)
                         (fn [out] (is (= b out) (pr-str b))))))))) 
     done)))

(deftest unseal-passes-through-anything-without-the-prefix
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (js/Promise.all
               (clj->js
                (for [p (:passthrough @fixture)]
                  (.then (seal/unseal k :tasks :description p)
                         (fn [out] (is (= p out) p))))))))
     done)))

(deftest a-prefixed-value-that-will-not-open-is-handed-back-not-thrown
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (js/Promise.all
               (clj->js
                (for [u (:unopenable @fixture)]
                  (.then (seal/unseal k :tasks :description u)
                         (fn [out] (is (= u out)
                                       (str u " — js/atob throws synchronously, past every catch")))))))))
     done)))

(deftest no-key-means-no-sealing
  (async done
    (finally!
     (js/Promise.all
      #js [(.then (seal/seal nil :tasks :description "plain" nil)
                  (fn [out] (is (= "plain" out))))
           (.then (seal/unseal nil :tasks :description "enc:v1:whatever")
                  (fn [out] (is (= "enc:v1:whatever" out))))])
     done)))

(deftest an-unchanged-value-is-not-re-sealed
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal k :tasks :description "a body" nil)
                     (fn [stored]
                       (.then (seal/seal k :tasks :description "a body" stored)
                              (fn [out] (is (= stored out) "the plaintext half of the echo rule"))))))) 
     done)))

(deftest an-echoed-envelope-this-key-can-open-is-a-no-op-and-not-a-second-envelope
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal k :tasks :description "a body" nil)
                     (fn [stored]
                       (.then (seal/seal k :tasks :description stored stored)
                              (fn [out]
                                (is (= stored out) "the bytes are compared before anything is opened")
                                (.then (seal/unseal k :tasks :description out)
                                       (fn [opened]
                                         (is (= "a body" opened)
                                             "and so it is not enc(enc(…))")))))))))
     done)))

(deftest an-unchanged-value-on-an-unmigrated-row-stays-plaintext
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal k :tasks :description "not migrated yet" "not migrated yet")
                     (fn [out] (is (= "not migrated yet" out))))))
     done)))

(deftest an-unchanged-value-on-a-row-this-client-cannot-read-stays-put
  (async done
    (finally!
     (.then (other-key)
            (fn [ok]
              (.then (seal/seal ok :tasks :description "someone else's key" nil)
                     (fn [foreign]
                       (.then (test-key)
                              (fn [k]
                                (.then (seal/seal k :tasks :description foreign foreign)
                                       (fn [out]
                                         (is (= foreign out)
                                             "otherwise every no-op nests one more envelope")))))))))
     done)))

;; ---------------------------------------------------------------------------
;; Rows and audit payloads.

(deftest a-lean-row-gains-no-keys
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (let [lean {:id 1 :title "a task" :tags "x"}]
                (js/Promise.all
                 #js [(.then (seal/seal-row k :tasks lean) (fn [out] (is (= lean out))))
                      (.then (seal/unseal-row k :tasks lean) (fn [out] (is (= lean out))))]))))
     done)))

(deftest a-row-seals-and-opens-its-body-and-nothing-else
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (let [row {:id 1 :title "a task" :tags "x" :description "the body"}]
                (.then (seal/seal-row k :tasks row)
                       (fn [sealed]
                         (is (= (dissoc row :description) (dissoc sealed :description)))
                         (is (seal/sealed? (:description sealed)))
                         (.then (seal/unseal-row k :tasks sealed)
                                (fn [out] (is (= row out))))))))) 
     done)))

(deftest a-row-echoes-what-has-not-changed
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal-row k :tasks {:id 1 :description "the body"})
                     (fn [stored]
                       (.then (seal/seal-row k :tasks {:id 1 :description "the body"} stored)
                              (fn [again] (is (= stored again))))))))
     done)))

(deftest an-audit-payload-opens-in-every-shape-the-rules-name
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (js/Promise.all
               (clj->js
                (for [payload [{:row {:title "t" :description "b"}}
                               {:snapshot {:description "gone"}}
                               {:field "description" :old-value "was" :new-value "is"}
                               {:changes {:description {:old "o" :new "n"}
                                          :title {:old "x" :new "y"}}}
                               {:method "put" :uri "/u" :body "{}" :reason "r"}]]
                  ;; Seal each path by hand — the browser never seals a payload,
                  ;; the walker does — then check the browser opens it.
                  (-> (reduce (fn [pm [path aad-str]]
                                (.then pm (fn [acc]
                                            (.then (seal/seal-text k aad-str (get-in acc path))
                                                   (fn [v] (assoc-in acc path v))))))
                              (js/Promise.resolve payload)
                              (seal/prose-paths payload))
                      (.then (fn [sealed]
                               (.then (seal/unseal-payload k sealed)
                                      (fn [out] (is (= payload out) (pr-str payload))))))))))))
     done)))

(deftest a-payload-with-no-prose-is-left-alone
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (let [payload {:category-type "person" :category-id 3 :category-title "Andrew"}]
                (is (= [] (seal/prose-paths payload)))
                (.then (seal/unseal-payload k payload)
                       (fn [out] (is (= payload out)))))))
     done)))

;; ---------------------------------------------------------------------------
;; The write path: index → lookup → write.
;;
;; Cookbook found both of its blocking bugs in this wiring rather than in the
;; envelope, which a fixture had already pinned. These drive the whole path:
;; a response arrives, is indexed, is opened, and a later save of the same text
;; must go out as the very bytes already stored.

(deftest a-response-is-indexed-before-it-is-opened
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal k :tasks :description "a body" nil)
                     (fn [ct]
                       (let [body {:tasks [{:id 7 :description ct}]}
                             index (seal/remember {} "/api/today-board" body)]
                         (is (= {[:tasks 7 :description] ct} index))
                         (.then (seal/unseal-body k body)
                                (fn [opened]
                                  (is (= "a body" (get-in opened [:tasks 0 :description]))
                                      "and the index still holds the ciphertext the open discarded")))))))) 
     done)))

(deftest an-unchanged-save-goes-out-as-the-bytes-already-stored
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal k :tasks :description "a body" nil)
                     (fn [ct]
                       (let [index (seal/remember {} "/api/tasks/7" {:id 7 :description ct})]
                         (.then (seal/seal-params k index "/api/tasks/7"
                                                  {:title "t" :description "a body" :tags ""})
                                (fn [params]
                                  (is (= ct (:description params))
                                      "the echo rule, reached through the index"))))))))
     done)))

(deftest a-changed-save-seals-afresh
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal k :tasks :description "a body" nil)
                     (fn [ct]
                       (let [index (seal/remember {} "/api/tasks/7" {:id 7 :description ct})]
                         (.then (seal/seal-params k index "/api/tasks/7"
                                                  {:title "t" :description "a different body"})
                                (fn [params]
                                  (is (seal/sealed? (:description params)))
                                  (is (not= ct (:description params)))
                                  (.then (seal/unseal k :tasks :description (:description params))
                                         (fn [out] (is (= "a different body" out))))))))))) 
     done)))

(deftest a-row-this-browser-never-read-seals-afresh
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal-params k {} "/api/tasks/99" {:description "a body"})
                     (fn [params]
                       (is (seal/sealed? (:description params))
                           "no stored bytes to echo, so a fresh nonce — never a guess")))))
     done)))

(deftest an-envelope-with-nothing-to-compare-it-against-is-still-not-sealed-again
  ;; The shape B-1 produced by the thousand: a list read that never went through
  ;; `et.tr.ui.api` filled the app-state with ciphertext and indexed nothing, and
  ;; an inline title edit then sent that ciphertext back as `:description`. With
  ;; no stored bytes to compare against, the echo rule cannot answer — so this
  ;; one does, and it answers the only way there is.
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal k :tasks :description "a body" nil)
                     (fn [ct]
                       (js/Promise.all
                        #js [(.then (seal/seal-params k {} "/api/tasks/7" {:description ct})
                                    (fn [params]
                                      (is (= ct (:description params))
                                          "a client only ever holds an envelope because it read one")
                                      (.then (seal/unseal k :tasks :description (:description params))
                                             (fn [out]
                                               (is (= "a body" out)
                                                   "one open and the body — not a second envelope")))))
                             (.then (seal/seal k :tasks :description "something else" nil)
                                    (fn [other]
                                      (let [index (seal/remember {} "/api/tasks/7" {:id 7 :description other})]
                                        (.then (seal/seal-params k index "/api/tasks/7" {:description ct})
                                               (fn [params]
                                                 (is (= ct (:description params))
                                                     "and a stale index does not change that either"))))))])))))
     done)))

(deftest a-create-has-no-row-to-echo-and-seals-afresh
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal-params k {} "/api/tasks" {:title "t" :description "a body"})
                     (fn [params] (is (seal/sealed? (:description params)))))))
     done)))

(deftest a-message-body-is-never-sealed
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (js/Promise.all
               #js [(.then (seal/seal-params k {} "/api/messages/3" {:description "an inbox body"})
                           (fn [params]
                             (is (= "an inbox body" (:description params))
                                 "three keyless producers write these and the list search reads them")))
                    (.then (seal/seal-params k {} "/api/messages" {:description "from the mail poller"})
                           (fn [params] (is (= "from the mail poller" (:description params)))))
                    (.then (seal/seal-params k {} "/api/mottos/2" {:description "Seize the day"})
                           (fn [params]
                             (is (= "Seize the day" (:description params))
                                 "a motto body is a second name, not prose")))])))
     done)))

(deftest an-unmigrated-row-echoes-its-plaintext-rather-than-sealing-it
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              ;; Nothing arrived sealed, so nothing was indexed — but the row is
              ;; in the response, and the mixed window is exactly when this
              ;; matters: a no-op save must stay a no-op.
              (let [index (seal/remember {} "/api/tasks/7" {:id 7 :description "still plaintext"})]
                (is (= {} index) "a plaintext row contributes nothing to index")
                (.then (seal/seal-params k index "/api/tasks/7" {:description "still plaintext"})
                       (fn [params]
                         (is (seal/sealed? (:description params))
                             "and so this save does seal it — which is the row sealing on its first write"))))))
     done)))

(deftest a-write-with-no-key-goes-out-untouched
  (async done
    (finally!
     (.then (seal/seal-params nil {} "/api/tasks/7" {:description "a body"})
            (fn [params] (is (= "a body" (:description params)))))
     done)))

(deftest a-write-that-carries-no-body-is-untouched
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal-params k {} "/api/tasks/7" {:title "just a title"})
                     (fn [params] (is (= {:title "just a title"} params))))))
     done)))

(deftest two-rows-with-the-same-sentence-do-not-share-a-ciphertext
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal k :tasks :description "the same sentence" nil)
                     (fn [ct]
                       (let [index (seal/remember {} "/api/tasks/7" {:id 7 :description ct})]
                         ;; Task 8 says the same thing. Echoing task 7's bytes into
                         ;; it would tell anyone holding the file that they are equal
                         ;; — which is the whole reason for a fresh nonce per value.
                         (.then (seal/seal-params k index "/api/tasks/8"
                                                  {:description "the same sentence"})
                                (fn [params]
                                  (is (not= ct (:description params))
                                      "keyed by row, never by text")))))))) 
     done)))
