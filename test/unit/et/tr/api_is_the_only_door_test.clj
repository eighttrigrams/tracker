(ns et.tr.api-is-the-only-door-test
  "One namespace makes tracker's HTTP calls, and this is what keeps that true.

  `et.tr.ui.api`'s own docstring opens *\"Every HTTP call the tracker UI makes,
  and the one place the seal meets them.\"* When the seal landed, that sentence
  was not true: eighteen ClojureScript namespaces referred `ajax.core` directly
  and thirteen of those read a response carrying a `description` for a sealed
  table. So the read path never ran on any list page in the app — Tasks, Meets,
  Issues, Journal Entries, the six Category pages, Reports — and `enc:v1:…` was
  rendered as the body.

  The second-order failure was the severe one and it had no error message. The
  stored-ciphertext index is fed only from inside `api.cljs`, so a raw list fetch
  indexed **nothing**; an inline title edit on such a card then sent the
  ciphertext it had been handed back through the *sealing* write path, with
  `stored` nil, and what got written was `enc(enc(…))`. The server guard waved it
  through, because `enc(enc(…))` is sealed. The audit log recorded a real change.
  Nothing reported an error, and the next read opened it once and rendered an
  envelope — which looks exactly like the first-order symptom, so the two
  disguised each other.

  ## Why this is a grep and not a behaviour test

  Because the defect is an *absence*. No assertion about how tracker fetches
  tasks can fail when somebody adds a nineteenth namespace that fetches meets its
  own way — the new call site is simply not covered by anything, which is the
  state the whole app was in. The only thing that catches an omission is a rule
  over the source tree.

  ## And why there is no allowlist

  The obvious softening is to permit the endpoints that carry no prose —
  translations, auth, sources. It is refused, and B-1 is what it looks like when
  that judgement goes stale: every one of those thirteen call sites was written
  by somebody who had no reason to think about a seal, because there was none
  yet. *This endpoint carries no prose* is true until the day it is not, and the
  day it is not, nothing says so. Going through the one door costs a keyless
  walk over a response with nothing sealed in it, which is what every response in
  this app is until the cutover runs.

  It lives in the JVM suite rather than the ClojureScript one deliberately:
  `make test` is what runs everywhere, and a control somebody can silently not
  run is not a control. It needs no ClojureScript runtime — it reads the tree."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private the-door
  "The one namespace entitled to `ajax.core`, as a path. Spelled once."
  "src/cljs/et/tr/ui/api.cljs")

(defn- cljs-sources []
  (->> (file-seq (io/file "src/cljs"))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".cljs"))))

(defn- repo-path [^java.io.File f]
  (str/replace (.getPath f) java.io.File/separator "/"))

(deftest ajax-core-is-reachable-from-one-namespace-and-no-other
  (let [offenders (->> (cljs-sources)
                       (remove #(= the-door (repo-path %)))
                       (filter #(str/includes? (slurp %) "ajax.core"))
                       (map repo-path)
                       sort
                       vec)]
    (is (= [] offenders)
        (str "These namespaces reach ajax.core directly. Every HTTP call the "
             "tracker UI makes goes through et.tr.ui.api, which is where the "
             "seal meets them: a read is unsealed and — just as load-bearing — "
             "indexed, so that a later write of an unchanged body can echo the "
             "bytes already stored instead of sealing a ciphertext a second "
             "time. A call site outside it is a page that renders enc:v1:… and "
             "a row an inline edit can corrupt with nothing to say so.\n  "
             (str/join "\n  " offenders)))))

(deftest the-door-itself-exists-and-is-the-one-that-holds-the-seal
  ;; Without this, deleting or renaming api.cljs would turn the test above
  ;; green by emptying the set it checks.
  (let [f (io/file the-door)]
    (is (.exists f) (str the-door " is the namespace the rule above excepts"))
    (let [src (slurp f)]
      (is (str/includes? src "ajax.core")
          "and it is still the one that talks to the network")
      (is (str/includes? src "seal/seal-params")
          "and still the place the write path meets the seal")
      (is (str/includes? src "seal/unseal-body")
          "and the read path"))))
