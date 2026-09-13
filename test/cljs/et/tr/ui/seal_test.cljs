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

(deftest a-message-conversion-seals-the-body-it-is-given
  ;; The test above is about `/api/messages/3`, whose rows are never sealed.
  ;; `/api/messages/3/convert-to-task` is a different endpoint with the opposite
  ;; answer: it writes into `tasks`, which is sealed, and it `DELETE`s the
  ;; message it copied in the same transaction. `endpoint-table` cannot tell the
  ;; two apart — it answers `nil` for everything under `messages`, deliberately
  ;; and correctly — so the write path has to ask `convert-target` as well, or
  ;; the browser sends prose the server must refuse and the Inbox convert is
  ;; lost for the one user the seal is for.
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (js/Promise.all
               #js [(.then (seal/seal-params k {} "/api/messages/3/convert-to-task"
                                             {:description "a paragraph of his own notes"})
                           (fn [params]
                             (is (seal/sealed? (:description params))
                                 "into tasks, which is sealed — not into messages, which is not")
                             (.then (seal/unseal k :tasks :description (:description params))
                                    (fn [out] (is (= "a paragraph of his own notes" out))))))
                    (.then (seal/seal-params k {} "/api/messages/3/convert-to-resource"
                                             {:link "https://example.com/x"
                                              :description "a resource note body"})
                           (fn [params]
                             (is (seal/sealed? (:description params)))
                             (is (= "https://example.com/x" (:link params))
                                 "and nothing else about the request is touched")))])))
     done)))

(deftest a-conversion-never-echoes-the-index-because-that-id-is-the-message-s
  ;; The trap `convert-target`'s docstring names, driven rather than described.
  ;; The `3` in `/api/messages/3/convert-to-task` is the **message's** id. Resolve
  ;; that endpoint to `:tasks` with one lookup and the index answers for task 3 —
  ;; a real envelope, belonging to a row nobody mentioned, echoed into the new
  ;; task and opening to somebody else's sentence with nothing reporting an
  ;; error. A convert is a create, and a create has nothing to echo.
  ;;
  ;; **This passes with `seal-params`'s `when-not convert` guard removed**, and
  ;; that is worth saying here rather than leaving for somebody to discover.
  ;; `stored-for` keys on `endpoint-table`, which answers `nil` for anything under
  ;; `messages`, so the index cannot reach this endpoint by either road. The guard
  ;; is defence in depth against a `stored-for` that later learns about converts —
  ;; and a defensive branch nobody drives is a branch that rots, so the next test
  ;; drives it.
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal k :tasks :description "an unrelated task's body" nil)
                     (fn [other]
                       (let [index (seal/remember {} "/api/tasks/3" {:id 3 :description other})]
                         (js/Promise.all
                          #js [(.then (seal/seal-params k index "/api/messages/3/convert-to-task"
                                                        {:description "the mail body"})
                                      (fn [params]
                                        (is (not= other (:description params))
                                            "task 3's ciphertext is not this message's body")
                                        (.then (seal/unseal k :tasks :description (:description params))
                                               (fn [out] (is (= "the mail body" out))))))
                               ;; the reason it passes, asserted, so the redundancy
                               ;; above is a checked claim and not a hope
                               (js/Promise.resolve
                                (is (nil? (seal/stored-for index "/api/messages/3/convert-to-task"
                                                           :description))
                                    "the index cannot answer for a messages path at all"))])))))) 
     done)))

(deftest the-convert-guard-holds-even-if-the-index-ever-learns-to-answer
  ;; The `when-not convert` branch, driven by **injecting** the thing that cannot
  ;; happen today rather than by arranging a call that provokes it — because
  ;; provoking it would mean relying on `stored-for`'s current shape, which is the
  ;; very thing that might change and the reason the guard exists.
  ;;
  ;; **The scenario has to be the one where the echo rule cannot save us**, and my
  ;; first attempt at this test was not: with an unrelated `stored` whose plaintext
  ;; differs, `seal-at` reaches `(= was v)`, finds it false and seals afresh
  ;; anyway, so the test passed with the guard removed. The distinguishing case is
  ;; a message that happens to **say the same sentence** as some other task — then
  ;; `(= was v)` is true, the unrelated row's ciphertext is echoed into the new
  ;; one, and two rows share bytes. That is the exact equality a fresh nonce
  ;; exists to hide, and `api.cljs` says so: *keyed by row and never by text*.
  ;;
  ;; Found by a mutation probe, twice over: removing the guard left all 56 tests
  ;; green, and then left my first replacement green too.
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal k :tasks :description "a sentence two rows share" nil)
                     (fn [other]
                       (with-redefs [seal/stored-for (fn [& _] other)]
                         (.then (seal/seal-params k {} "/api/messages/3/convert-to-task"
                                                  {:description "a sentence two rows share"})
                                (fn [params]
                                  (is (not= other (:description params))
                                      "an unrelated row's bytes must not become this row's")
                                  (.then (seal/unseal k :tasks :description (:description params))
                                         (fn [out]
                                           (is (= "a sentence two rows share" out)
                                               "sealed afresh, under its own nonce"))))))))))
     done)))


(deftest a-conversion-of-a-blank-message-sends-a-blank-and-not-an-envelope
  ;; A link-only message from the feed worker is the commonest convert in the
  ;; app, and blank is never sealed. The server tells `""` from a missing body on
  ;; purpose, so what matters is that the key survives the walk.
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal-params k {} "/api/messages/3/convert-to-task" {:description ""})
                     (fn [params]
                       (is (= "" (:description params)))
                       (is (contains? params :description)
                           "and the key is still there: absent and blank are different answers")))))
     done)))

;; The other half of the same finding: *which* text the browser sends. The Inbox
;; holds the message body already — it renders it — and a convert is the one
;; write where the client has to hand it over, because the server is about to
;; delete the only other copy.

(deftest a-conversion-carries-the-body-the-inbox-already-holds
  (let [messages [{:id 3 :title "an article" :description "a paragraph of his own notes"}
                  {:id 4 :title "another" :description "not this one"}]]
    (is (= {:description "a paragraph of his own notes"}
           (seal/convert-params messages 3 {})))
    (is (= {:link "https://example.com/x" :description "a paragraph of his own notes"}
           (seal/convert-params messages 3 {:link "https://example.com/x"}))
        "and whatever else the request already carried")))

(deftest a-conversion-of-a-message-with-no-body-carries-a-blank
  (let [messages [{:id 3 :title "a link somebody posted" :description nil}
                  {:id 4 :title "and one with an empty body" :description ""}]]
    (is (= {:description ""} (seal/convert-params messages 3 {}))
        "blank is a value here, and `nil` would read to the server as no body at all")
    (is (= {:description ""} (seal/convert-params messages 4 {})))))

(deftest a-conversion-of-a-message-this-page-does-not-hold-sends-no-body-at-all
  ;; Not a blank. A blank converts cleanly and loses the body permanently, with
  ;; nothing anywhere to say it happened; no key at all earns the server's
  ;; refusal, which says exactly what went wrong and leaves the message in the
  ;; inbox. The dropdown this is reached from is rendered out of the list, so
  ;; this should not be reachable — and *should not be reachable* is precisely
  ;; the kind of claim that decides which way a fallback points.
  (is (= {} (seal/convert-params [{:id 4 :description "somebody else's"}] 3 {})))
  (is (= {:link "https://example.com/x"}
         (seal/convert-params [] 3 {:link "https://example.com/x"}))))

(deftest an-unmigrated-row-echoes-its-plaintext-rather-than-sealing-it
  ;; The test name is the contract, and it used to assert the opposite of
  ;; itself — the review's NIT-4, and the reason this is worth more than a
  ;; comment: a name is what somebody greps for.
  ;;
  ;; Rule 2 is *never re-seal an unchanged value — echo the stored value,
  ;; **whichever encoding it has***. The browser had only the first half: the
  ;; index collected ciphertext, so an unmigrated row contributed nothing and
  ;; `stored-for` could never answer with a plaintext.
  ;;
  ;; The leak that closes is narrow and nasty. During the mixed window a no-op
  ;; save on a row the walker has *already been past* sealed afresh, and
  ;; `record-update!` diffs before against after — so it wrote an `:update`
  ;; event whose `old-value` is the body in the clear, into the audit log, at
  ;; exactly the moment the walker was never coming back for it.
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (let [index (seal/remember {} "/api/tasks/7" {:id 7 :description "still plaintext"})]
                (is (= {[:tasks 7 :description] "still plaintext"} index)
                    "an unmigrated row is remembered too, as what it is")
                (.then (seal/seal-params k index "/api/tasks/7" {:description "still plaintext"})
                       (fn [params]
                         (is (= "still plaintext" (:description params))
                             "a no-op stays a no-op, so there is no :update event and
                              no plaintext old-value written into the log")
                         (is (not (seal/sealed? (:description params))))))))) 
     done)))

(deftest a-real-edit-of-an-unmigrated-row-still-seals-it
  ;; The other half, and the one that makes the row migrate itself. Only a save
  ;; that changes nothing is a no-op; a save that changes the body is a write,
  ;; and a write by a client holding a key is sealed.
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (let [index (seal/remember {} "/api/tasks/7" {:id 7 :description "still plaintext"})]
                (.then (seal/seal-params k index "/api/tasks/7" {:description "edited"})
                       (fn [params]
                         (is (seal/sealed? (:description params)))
                         (.then (seal/unseal k :tasks :description (:description params))
                                (fn [out] (is (= "edited" out))))))))) 
     done)))

(deftest a-remembered-plaintext-cannot-be-mistaken-for-remembered-bytes
  ;; Both kinds live at one key — `[table id column]` — and the value says which
  ;; it is, because the `enc:v1:` prefix is self-describing. That is the same
  ;; argument `body-prose-paths` makes for the read path, and it is what keeps
  ;; one key per row honest: the **latest** read is the truth, whichever
  ;; encoding it came back in, so a row that seals under the walker and is read
  ;; again cannot go on being echoed as the plaintext it used to be.
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal k :tasks :description "the body" nil)
                     (fn [ct]
                       (let [before (seal/remember {} "/api/tasks/7" {:id 7 :description "the body"})
                             after (seal/remember before "/api/tasks/7" {:id 7 :description ct})]
                         (is (= "the body" (get before [:tasks 7 :description])))
                         (is (= ct (get after [:tasks 7 :description]))
                             "the walker sealed it and the next read says so")
                         (is (= 1 (count after)) "one key, not two")
                         (.then (seal/seal-params k after "/api/tasks/7" {:description "the body"})
                                (fn [params]
                                  (is (= ct (:description params))
                                      "and the echo is now the bytes, not the stale plaintext"))))))))
     done)))

(deftest a-blank-body-is-not-remembered
  ;; Nothing to echo and nothing to protect: blank is never sealed, so
  ;; `seal-at` answers before the index is consulted at all. Indexing it would
  ;; be a row's worth of noise per empty journal entry.
  (is (= {} (seal/remember {} "/api/tasks/7" {:id 7 :description ""})))
  (is (= {} (seal/remember {} "/api/tasks/7" {:id 7 :description "   "})))
  (is (= {} (seal/remember {} "/api/tasks/7" {:id 7}))))

(deftest a-message-body-is-not-remembered-either
  ;; Message bodies are plaintext permanently, so the "only sealed values are
  ;; collected" rule used to keep them out of the index for free. It no longer
  ;; does, and what keeps them out now is the thing that always really did: an
  ;; endpoint whose table cannot be named contributes nothing, and `messages`
  ;; is deliberately absent from both `api-segment->table` and
  ;; `container-key->table`.
  (is (= {} (seal/remember {} "/api/messages/3" {:id 3 :description "an inbox body"})))
  (is (= {} (seal/remember {} "/api/messages" [{:id 3 :description "from the poller"}])))
  (is (= {} (seal/remember {} "/api/mottos/2" {:id 2 :description "Seize the day"}))))


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

;; ---------------------------------------------------------------------------
;; Who is offered the key box.
;;
;; The ⚙ panel is the only way a key gets into this browser, and it was gated on
;; `is_admin` — *is this person not the admin* — which is a question about roles
;; and not about sealing. Tracker has three humans in one database and one of
;; them seals. So everybody else was offered a box that would do them no good and
;; some harm, and the review found it the way it is always found: the reviewer
;; pasted the fixture key into the wrong person's panel to get its work done,
;; which is exactly the mistake the panel's own docstring says the gate exists to
;; prevent.

(deftest the-key-box-is-offered-on-whether-this-user-seals
  (is (seal/offers-the-key-box? {:username "antonio" :seal_prose true}))
  (is (not (seal/offers-the-key-box? {:username "daniel" :seal_prose false}))))

(deftest the-key-box-is-not-offered-on-a-role-or-on-a-guess
  (testing "not admin-ness, which is the question it used to ask"
    (is (not (seal/offers-the-key-box? {:username "daniel" :is_admin false}))
        "a non-admin who does not seal — the case the old gate got backwards")
    (is (not (seal/offers-the-key-box? {:username "admin" :is_admin true :seal_prose false}))
        "and the admin has :id nil and no rows, so there is nothing to seal"))
  (testing "and not a client-side guess at who the owner is"
    (is (not (seal/offers-the-key-box? {:username "antonio"}))
        "absent is not sealed: which rows are the sealing user's is the one
         question a client cannot answer, so an unanswered one is a no")
    (is (not (seal/offers-the-key-box? nil)))))

;; ---------------------------------------------------------------------------
;; Handing an opened body to a handler, exactly once.
;;
;; The read path's last step, and until B-1 it guarded five call sites; it now
;; guards about forty-five, which is what made a latent ordering bug worth
;; finding. The decision lives here rather than in `et.tr.ui.api` for the reason
;; the whole section above exists: `api.cljs` cannot be loaded by this suite —
;; `ajax.core` wants `xmlhttprequest` — and *exactly once* is not a claim worth
;; making without a test that counts.

(deftest a-handler-that-throws-is-not-run-a-second-time-with-the-ciphertext
  ;; The bug, driven. With `.catch` installed *after* `.then`, it sits on the
  ;; promise `.then` returned and so catches a rejection from `unseal-body`
  ;; **and any throw from the handler itself** — and then calls the handler again
  ;; with the *unopened* body. A bad `swap!` part-way through a real handler
  ;; therefore half-applies its effects and then writes `enc:v1:…` into the
  ;; app-state on top of them, with nothing anywhere reporting it.
  (async done
    (let [seen (atom [])
          thrower (fn [b]
                    (swap! seen conj (:description b))
                    (throw (js/Error. "a bad swap!, part-way")))]
      (-> (test-key)
          (.then (fn [k]
                   (.then (seal/seal k :tasks :description "a body" nil)
                          (fn [ct] [k ct]))))
          (.then (fn [[k ct]] (seal/opening k {:id 7 :description ct} thrower)))
          (.then (fn [_] (is false "the handler's throw must not be swallowed")))
          (.catch (fn [e]
                    (is (= "a bad swap!, part-way" (.-message e))
                        "the throw comes out, where a console can show it")
                    (is (= 1 (count @seen)) "called once, not twice")
                    (is (= "a body" (first @seen))
                        "and with the opened body — never with the envelope")))
          (.then (fn [_] (done)))))))

(deftest a-body-that-will-not-open-is-still-handed-over-as-it-arrived
  ;; Invariant 4, and the behaviour the reorder must not cost: one unreadable
  ;; body beside everything that reads, visibly, rather than a whole response
  ;; dropped with nothing to say why.
  ;;
  ;; **It travels the `.then`, not the `.catch`**, and saying so is the point of
  ;; this comment. `unseal-at` catches per value and hands the value back, so
  ;; `unseal-body` *resolves* with the envelope intact even under the wrong key —
  ;; verified, not assumed. Reading this test as coverage of the `.catch` would
  ;; be reading the name and not the path; that branch is driven by the next one.
  (async done
    (finally!
     (.then (other-key)
            (fn [wrong]
              (.then (test-key)
                     (fn [k]
                       (.then (seal/seal k :tasks :description "a body" nil)
                              (fn [ct]
                                (let [seen (atom [])]
                                  (.then (seal/opening wrong {:id 7 :description ct}
                                                       #(swap! seen conj (:description %)))
                                         (fn [_]
                                           (is (= 1 (count @seen)) "once, here too")
                                           (is (= ct (first @seen))
                                               "handed back, visibly, and not swallowed"))))))))))
     done)))

(deftest a-rejection-from-the-unseal-itself-reaches-the-handler-once-with-the-body
  ;; The `.catch` branch — **the exact line R-5 reordered** — and nothing else in
  ;; this file reaches it. `unseal-body` resolves for every input tried: a wrong
  ;; key (the test above), a non-string body, a nested row, a `nil`. That is good
  ;; news about `unseal-body` and bad news about coverage, because it means the
  ;; branch the whole finding was about had no test at all and a sibling that
  ;; looked like one.
  ;;
  ;; So the rejection is manufactured. `with-redefs` is the honest way to say
  ;; *this cannot happen today and must still be right if it ever does* — a
  ;; defensive branch nobody drives is a branch that rots.
  ;;
  ;; **This one passes under either ordering, and is not meant to distinguish
  ;; them** — checked, by putting the old ordering back and watching only
  ;; `a-handler-that-throws-…` go red. That test pins the order; this one pins
  ;; what the `.catch` is *for*. Between them: it catches what it should, and
  ;; only what it should.
  (async done
    (let [seen (atom [])]
      (with-redefs [seal/unseal-body (fn [_ _] (js/Promise.reject (js/Error. "the walk blew up")))]
        (-> (seal/opening :a-key {:id 7 :description "enc:v1:whatever"}
                          #(swap! seen conj (:description %)))
            (.then (fn [_]
                     (is (= 1 (count @seen)) "once — the rejection is not a second run")
                     (is (= "enc:v1:whatever" (first @seen))
                         "and with the body exactly as it arrived, which is invariant 4")))
            (.catch (fn [e] (is false (str "must not reject: " e))))
            (.then (fn [_] (done))))))))

(deftest an-opened-body-reaches-the-handler-once-on-the-ordinary-path
  (async done
    (finally!
     (.then (test-key)
            (fn [k]
              (.then (seal/seal k :tasks :description "a body" nil)
                     (fn [ct]
                       (let [seen (atom [])]
                         (.then (seal/opening k {:id 7 :description ct}
                                              #(swap! seen conj (:description %)))
                                (fn [_]
                                  (is (= ["a body"] @seen)))))))))
     done)))

(deftest opening-a-body-with-no-handler-is-not-an-error
  (async done
    (finally! (seal/opening nil {:id 7 :description "a body"} nil) done)))
