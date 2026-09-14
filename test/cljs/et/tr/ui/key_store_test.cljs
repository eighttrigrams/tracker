(ns et.tr.ui.key-store-test
  "The key is held here; whether it may be *used to seal* is held here too.

  F-1: after `--disarm` the flag goes to 0 and nothing tells the browser. The key
  survives in IndexedDB by a deliberate decision — signing out is not handing the
  machine over — so the browser went on sealing every description for a user the
  server had just stopped accepting envelopes from, and the ⚙ panel that could
  have cleared the key was gone, because it is gated on the flag that was turned
  off. Recovery from the recovery was devtools.

  The gate is on **sealing only**. A user whose flag is off may still be reading
  rows that are sealed — that is the whole of the mixed-state window, and of the
  moment between `--disarm` and `--unseal` — so opening stays ungated. Gating the
  read would turn the escape hatch into `enc:v1:…` on screen, which is the
  mirror-image bug."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [cljs.reader :as reader]
            [et.tr.ui.key-store :as key-store]
            [et.tr.ui.seal :as seal]))

(def ^:private fixture
  (delay (reader/read-string (.readFileSync (js/require "fs")
                                            "test/fixtures/seal-vectors.edn" "utf8"))))

(defn- b64->bytes [s]
  (let [bin (js/atob s) out (js/Uint8Array. (.-length bin))]
    (dotimes [i (.-length bin)] (aset out i (.charCodeAt bin i)))
    out))

(defn- test-key [] (seal/import-key (b64->bytes (:key-base64 @fixture))))

;; A **map** fixture, not a function one: `:each` with a function wrapper cannot
;; be used with `async` tests — cljs.test aborts the run rather than let a
;; fixture return before the test it wraps has finished.
(use-fixtures :each
  {:before (fn []
             (reset! key-store/state {:status :absent :key nil :fingerprint nil})
             (key-store/set-seals! false))})

(defn- armed! [k on?]
  (reset! key-store/state {:status :present :key k :fingerprint "ce63872e"})
  (key-store/set-seals! on?))

;; ---------------------------------------------------------------------------

(deftest the-flag-starts-unset-so-a-cold-load-seals-nothing
  ;; `core.cljs` loads the key at startup, **before anyone has logged in** and
  ;; therefore before `/api/auth/me` can have answered. The default has to be one
  ;; of two wrong-in-a-window answers and this is the safer one — see the
  ;; docstring on `seals?`.
  (is (false? (key-store/seals-now?)))
  (is (nil? (key-store/sealing-key)) "no flag, no sealing, even with no key at all"))

(deftest a-key-with-the-flag-unset-seals-nothing
  ;; The F-1 state exactly: a key in the browser, a user whose `seal_prose` is 0.
  (async done
    (.then (test-key)
           (fn [k]
             (armed! k false)
             (is (some? (key-store/current-key))
                 "the key is still here — nothing was deleted")
             (is (nil? (key-store/sealing-key))
                 "but it may not be used to seal")
             (.then (seal/seal-params (key-store/sealing-key) {} "/api/tasks/7"
                                      {:title "t" :description "a body"})
                    (fn [params]
                      (is (= "a body" (:description params))
                          "the write goes out as prose, which is what the server now expects")
                      (is (not (seal/sealed? (:description params))))
                      (done)))))))

(deftest a-key-with-the-flag-set-still-seals
  (async done
    (.then (test-key)
           (fn [k]
             (armed! k true)
             (is (some? (key-store/sealing-key)))
             (.then (seal/seal-params (key-store/sealing-key) {} "/api/tasks/7"
                                      {:title "t" :description "a body"})
                    (fn [params]
                      (is (seal/sealed? (:description params))
                          "the ordinary armed path is untouched")
                      (.then (seal/unseal k :tasks :description (:description params))
                             (fn [out] (is (= "a body" out)) (done)))))))))

(deftest reading-is-not-gated-because-the-mixed-window-needs-it
  ;; The requirement that makes this fix safe rather than a second bug. Between
  ;; `--disarm` and `--unseal` the flag is 0 and the rows are still sealed; a
  ;; browser that stopped opening them would show `enc:v1:…` for prose the user
  ;; can perfectly well read.
  (async done
    (.then (test-key)
           (fn [k]
             (.then (seal/seal k :tasks :description "still sealed on disk" nil)
                    (fn [ct]
                      (armed! k false)
                      (is (some? (key-store/current-key))
                          "the read path still gets the key")
                      (.then (seal/opening (key-store/current-key)
                                           {:id 7 :description ct}
                                           identity)
                             (fn [opened]
                               (is (= "still sealed on disk" (:description opened))
                                   "prose on screen, not an envelope")
                               (done)))))))))

(deftest the-flag-follows-the-user-and-is-dropped-when-unknown
  (async done
    (.then (test-key)
           (fn [k]
             (armed! k true)
             (is (some? (key-store/sealing-key)))
             (testing "whoever the next answer is about, until it arrives we do not know"
               (key-store/set-seals! false)
               (is (nil? (key-store/sealing-key))))
             (testing "and a nil or absent flag is not a yes"
               (key-store/set-seals! nil)
               (is (false? (key-store/seals-now?)))
               (key-store/set-seals! (:seal_prose {:username "antonio"}))
               (is (false? (key-store/seals-now?))))
             (testing "only a true answer arms it"
               (key-store/set-seals! (:seal_prose {:username "daniel" :seal_prose true}))
               (is (some? (key-store/sealing-key))))
             (done)))))
