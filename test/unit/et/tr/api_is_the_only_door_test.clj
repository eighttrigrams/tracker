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
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
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

(defn requires-ajax?
  "Whether an `ns` form **requires** `ajax.core`.

  It used to be `(str/includes? src \"ajax.core\")` over the whole file, and the
  first namespace to *document* why it goes through the door was flagged as
  going around it: `state/mail.cljs`'s docstring says, in prose, that it reaches
  `ajax.core` through `api` — which is the very thing this rule wants to be
  true. A control that fails on a correct sentence is a control the next person
  deletes the sentence to satisfy, and the sentence was worth more than the
  grep's simplicity.

  So the question is asked of the form and not of the bytes, and of the
  **symbol**. A docstring is a string and is skipped for free; a comment never
  reaches the reader at all. It is *stricter* rather than looser — every way of
  spelling the require names that symbol, because naming it is what a require is:

      (:require [ajax.core :refer [GET POST]])
      (:require [ajax.core :as ajax])
      (:require ajax.core)

  There is still **no allowlist** and no exception beyond `the-door` itself. The
  softening this refuses is the one the namespace docstring above argues against,
  and nothing here has changed about it."
  [ns-form]
  (->> ns-form
       (tree-seq coll? seq)
       (filter symbol?)
       (some #(= 'ajax.core %))
       boolean))

(defn- ns-form-of
  "One file's `ns` form, read as data — with the path named whenever it cannot
  be, because a reader exception from nowhere is the least useful failure a
  control over a source tree can produce.

  It **insists the first form is an `ns` form**, and that is not pedantry. The
  reader reads one form, so a file that led with a `(comment …)` or a `def`
  would have its requires read straight past and leave the rule answering *no*
  while genuinely reaching the network — a rule anybody could step around by
  moving a line. Every namespace in this tree leads with its `ns` form. One that
  stops has to say so here rather than quietly drop out of the set.

  `clojure.edn/read-string` rather than `clojure.core`'s, because this reads
  files off a disk and `*read-eval*` has no business anywhere near that."
  [^java.io.File f]
  (let [path (repo-path f)
        form (try (edn/read-string (slurp f))
                  (catch Exception e
                    (throw (ex-info (str "could not read the first form of " path
                                         " — this rule reads every .cljs file under src/cljs")
                                    {:path path} e))))]
    (when-not (and (seq? form) (= 'ns (first form)))
      (throw (ex-info (str path " does not lead with its ns form, so this rule would read "
                           "past its requires and answer for something else entirely")
                      {:path path})))
    form))

(deftest ajax-core-is-reachable-from-one-namespace-and-no-other
  (let [offenders (->> (cljs-sources)
                       (remove #(= the-door (repo-path %)))
                       (filter #(requires-ajax? (ns-form-of %)))
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

(deftest the-rule-knows-a-require-from-a-mention-of-one
  ;; The case that was missed, spelled out so it cannot be missed twice. This is
  ;; the re-review's NIT-7 in its concrete form — the rule did not know what a
  ;; require looks like — and it was found by a docstring that was *right*.
  (let [asked #(requires-ajax? (edn/read-string %))]
    (testing "every spelling of the require is caught"
      (is (asked "(ns x.y (:require [ajax.core :refer [GET POST]]))"))
      (is (asked "(ns x.y (:require [ajax.core :as ajax] [other :as o]))"))
      (is (asked "(ns x.y (:require ajax.core))")))
    (testing "and prose about it, anywhere, is not one"
      (is (not (asked "(ns x.y \"This reaches `ajax.core` through et.tr.ui.api.\"
                         (:require [et.tr.ui.api :as api]))")))
      (is (not (asked "(ns x.y (:require [et.tr.ui.api :as api]))"))))))

(deftest a-file-that-does-not-lead-with-its-ns-form-is-not-silently-excused
  ;; The reader reads one form. Without this, moving the `ns` form down a line —
  ;; below a `(comment …)`, below a `def` — would take a namespace out of the
  ;; rule's reach entirely, answering *no* while requiring ajax.core on the line
  ;; below. The failure has to be loud and has to name the file.
  ;;
  ;; **What is asserted is that it throws and names the file**, not which of
  ;; `ns-form-of`'s two guards did it. Both are correct outcomes and which one
  ;; fires is not this test's business: the empty case reaches the *leading form*
  ;; guard only because `clojure.edn/read-string` answers `nil` for empty input
  ;; rather than throwing, and pinning that would make a red here mean *the
  ;; reader changed* rather than *a file escaped the rule*. Neighbouring advice,
  ;; from an agent whose suite was green in this box and red on the host over a
  ;; `babashka.cli` message: never assert a dependency's wording, and assert its
  ;; behaviour only where a contract of ours rests on it. Nothing of ours rests
  ;; on which guard speaks first.
  (let [tmp (java.io.File/createTempFile "door" ".cljs")
        named-the-file? (fn [f]
                          (try (ns-form-of f) false
                               (catch clojure.lang.ExceptionInfo e
                                 (str/includes? (ex-message e) (repo-path f)))))]
    (try
      (spit tmp "(comment \"moved out of the way\")\n(ns x.y (:require [ajax.core :refer [GET]]))")
      (is (named-the-file? tmp)
          "a namespace hidden behind a leading form is refused, by name")
      (spit tmp "")
      (is (named-the-file? tmp)
          "and so is an empty one — rather than an EOF from nowhere")
      (spit tmp "(ns x.y (:require [et.tr.ui.api :as api]))")
      (is (not (named-the-file? tmp))
          "and an ordinary namespace is read without complaint, so the two above
           are the guard firing and not the helper refusing everything")
      (finally (.delete tmp)))))

(deftest every-cljs-file-in-the-tree-leads-with-a-readable-ns-form
  ;; The rule above is only a rule over the files it can actually read, so that
  ;; is asserted rather than assumed.
  (is (every? #(seq? (ns-form-of %)) (cljs-sources)))
  (is (pos? (count (cljs-sources))) "and there are some"))

(deftest the-door-itself-exists-and-is-the-one-that-holds-the-seal
  ;; Without this, deleting or renaming api.cljs would turn the test above
  ;; green by emptying the set it checks.
  (let [f (io/file the-door)]
    (is (.exists f) (str the-door " is the namespace the rule above excepts"))
    (is (requires-ajax? (ns-form-of f))
        "and it is still the one that talks to the network — asked with the same
         predicate, so a predicate that stopped seeing a require would fail here
         rather than quietly pass everywhere")
    ;; Both halves of the seal are still met here. These are greps and are
    ;; allowed to be: they stand for *the seal is still wired into this file*,
    ;; and if one is renamed the right answer is to come here and say what it was
    ;; renamed to, not to have the control quietly stop checking.
    (let [src (slurp f)]
      (is (str/includes? src "seal/seal-params")
          "and still the place the write path meets the seal")
      (is (str/includes? src "seal/opening")
          "and the read path — `opening` rather than `unseal-body` since R-5,
           because *exactly once* is a claim that needed a seam the node suite
           can load, and `et.tr.ui.seal` is that seam"))))
