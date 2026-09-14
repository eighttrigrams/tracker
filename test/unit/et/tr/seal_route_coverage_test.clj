(ns et.tr.seal-route-coverage-test
  "The completeness control `api-segment->table` did not have.

  F-2 of `handoffs/tracker-seal-final-review.md`: of the three vocabularies in
  `et.tr.seal-rules`, this is the only one whose omission no suite noticed. The
  reviewer deleted `\"meets\" :meets` from it and ran all three suites — every
  one stayed green — and then measured what the deletion costs:

      with \"meets\" :meets present  → 400, the guard refuses
      with that one line deleted    → 200, and meets.description holds
                                      \"Meet notes: the salary number we agreed.\"

  One line, no test red anywhere, and a sealing user's prose in the clear behind
  a 200. The map is read by **both** halves of *the client seals, the server
  enforces* — `seal.cljs seal-params` and `envelope.clj refusal-for` both go
  through `endpoint-table` — so a nil there means the client sends the plaintext
  it is holding *and* the server does not refuse it. One file, three readers, and
  therefore no second opinion anywhere.

  ## What this crosses, and why that is the whole point

  The lesson this feature already paid for once was *a completeness check that
  derived the list it checked from the thing being checked*. Such a check passes
  by construction and is worse than none, because it reads like cover.

  So the list here comes from **`src/clj/et/tr/server.clj`'s route table, read
  off disk as source**, and the classification comes from `et.tr.seal-rules`.
  Neither is derived from the other. A route is in scope when its handler — or
  anything the handler delegates to inside its own namespace — mentions a column
  that `sealed-columns` names. Adding an endpoint that takes a `description` and
  forgetting the vocabulary now fails here, and so does deleting an entry that a
  live route still needs.

  ## What it cannot catch, stated plainly

  1. **A handler that takes prose under a name `sealed-columns` does not know.**
     The scan looks for the sealed column *keywords*; a body field called
     `:notes` that lands in `tasks.description` is invisible to it. The column
     inventory is pinned elsewhere (`seal-vectors.edn`), which is what makes that
     acceptable rather than merely unfortunate.

  2. **Delegation out of the handler's own namespace.** The closure follows
     `update-place-handler → update-category-handler*` because both live in
     `category_handler.clj`; it stops at the namespace boundary. Widening it to
     all of `et.tr.server.*` was tried and rejected: one `:description` appearing
     in `server/common.clj` would then flag all 135 write routes at once and turn
     this control into noise, which is how controls die.

  3. **A route registered somewhere other than `defroutes api-routes`.** There is
     no such route today; a second `defroutes` would be invisible here.

  4. **Whether the classification is *right* beyond the sealed/clear split.**
     `the-two-segment-maps-agree-with-the-table-inventory` holds the other end of
     that: a segment moved from the sealed map to the clear one is caught there,
     because the table it names is checked against `sealed-columns` and
     `clear-tables` rather than taken on trust.

  Demonstrated by breaking it, both ways — see the report in
  `handoffs/tracker-seal-F2-route-coverage-report.md` for the transcript."
  (:require [clojure.java.io :as io]
            [clojure.repl :as repl]
            [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [et.tr.seal-rules :as rules]
            [et.tr.server]))

;; ---------------------------------------------------------------------------
;; One side: the routes, read off the source file.

(def ^:private routes-source "src/clj/et/tr/server.clj")

(defn- read-forms
  "Every top-level form of a source file, read as data. `*read-eval*` is off:
  this file is being read as a **description of routes**, not run, and a read
  that could evaluate is a different and much larger promise."
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (binding [*read-eval* false]
      (doall (take-while #(not= ::eof %)
                         (repeatedly #(read {:eof ::eof :read-cond :allow} r)))))))

(defn- route-form
  "The `(defroutes api-routes …)` form, or nil. Nil is a failure and is asserted
  on: a rename that quietly emptied this control is exactly the shape of thing
  the control exists to prevent."
  []
  (->> (read-forms routes-source)
       (filter #(and (seq? %) (= 'defroutes (first %)) (= 'api-routes (second %))))
       first))

(defn- walk-routes
  "`[{:method :path :handler} …]` out of one compojure form. `context` carries a
  prefix down; a method form is a leaf."
  [form prefix]
  (when (seq? form)
    (cond
      (= 'context (first form))
      (mapcat #(walk-routes % (str prefix (nth form 1))) (drop 3 form))

      (#{'GET 'POST 'PUT 'DELETE 'PATCH} (first form))
      [{:method (first form) :path (str prefix (nth form 1)) :handler (nth form 3)}]

      :else nil)))

(def ^:private all-routes
  (delay (vec (walk-routes (nth (route-form) 2) ""))))

(def ^:private write-routes
  (delay (filterv #(#{'POST 'PUT 'PATCH} (:method %)) @all-routes)))

;; ---------------------------------------------------------------------------
;; Which of them can carry prose — asked of the handler code, not of the map.

(defn- syms [form] (filter symbol? (tree-seq coll? seq form)))

(defn- handler-ns? [n] (str/starts-with? (str n) "et.tr.server"))

(defn- source-of [^clojure.lang.Var v]
  (try (repl/source-fn (symbol (str (ns-name (.ns v))) (name (.sym v))))
       (catch Throwable _ nil)))

(defn- handler-sources
  "Every source text reachable from one route's handler position, following
  delegation **within each root's own namespace** and no further.

  The roots are the symbols in the handler form that resolve, in `et.tr.server`,
  to a var in an `et.tr.server*` namespace — which covers both the plain
  `meet-handler/update-meet-handler` and the inline
  `(fn [req] (category-handler/reorder-category-handler req db.category/list-people))`,
  while leaving the `db.*` var in that second form out of it. A `db` namespace
  mentions every column there is; including one would make every route look like
  it carries prose."
  [handler]
  (let [roots (keep #(let [v (try (ns-resolve (find-ns 'et.tr.server) %) (catch Throwable _ nil))]
                       (when (and (var? v) (handler-ns? (ns-name (.ns ^clojure.lang.Var v)))) v))
                    (syms handler))]
    (mapcat
     (fn [^clojure.lang.Var root]
       (let [home (.ns root)]
         (loop [queue [root] seen #{} acc []]
           (if (empty? queue)
             acc
             (let [[^clojure.lang.Var v & more] queue]
               (if (or (seen v) (not= home (.ns v)))
                 (recur more seen acc)
                 (let [src (source-of v)
                       kids (keep #(let [x (try (ns-resolve home %) (catch Throwable _ nil))]
                                     (when (var? x) x))
                                  (syms (try (read-string {:read-cond :allow} src)
                                             (catch Throwable _ nil))))]
                   (recur (into (vec more) kids) (conj seen v) (conj acc src)))))))))
     roots)))

(def ^:private sealed-column-keywords
  "`#{\":description\"}` today. Taken from `sealed-columns`, which is the column
  inventory and not the segment map — so the list of routes and the thing being
  checked still come from different places."
  (delay (into #{} (map #(str ":" (name %)))
               (distinct (mapcat val rules/sealed-columns)))))

(defn- carries-prose? [route]
  (boolean (some (fn [src] (and src (some #(str/includes? src %) @sealed-column-keywords)))
                 (handler-sources (:handler route)))))

(def ^:private prose-routes
  (delay (filterv carries-prose? @write-routes)))

;; ---------------------------------------------------------------------------
;; The control itself is only worth as much as its own inputs, so those first.

(deftest the-route-table-was-actually-read
  (is (some? (route-form))
      "`defroutes api-routes` was not found in server.clj — renamed, moved, or
       the path is wrong, and this whole namespace is a no-op until it is fixed")
  (is (< 150 (count @all-routes))
      "far fewer routes than tracker has; the walk is not descending")
  (is (< 100 (count @write-routes))))

(deftest the-handler-sources-were-actually-read
  ;; Without this, a classpath that stopped carrying `src/clj` would make
  ;; `source-fn` return nil everywhere, no route would look like it carries
  ;; prose, and the control below would pass by finding nothing — which is the
  ;; failure it exists to prevent, wearing the costume of a green suite.
  (is (<= 15 (count @prose-routes))
      "no route looks like it carries prose, which cannot be true — clojure.repl/source-fn
       is probably returning nil because the sources are not on the classpath")
  (doseq [p ["/api/tasks/:id" "/api/meets/:id" "/api/journal-entries/:id"
             "/api/places/:id" "/api/messages/:id"
             "/api/messages/:id/convert-to-task"]]
    (is (contains? (set (map :path @prose-routes)) p)
        (str p " no longer reads as prose-carrying; either the handler changed or "
             "the source scan has stopped working"))))

;; ---------------------------------------------------------------------------
;; The control.

(deftest every-prose-carrying-write-route-is-classified-by-the-vocabulary
  (let [unclassified (remove #(rules/endpoint-disposition (:path %)) @prose-routes)]
    (is (empty? (map :path unclassified))
        (str "These routes accept a sealed column and `endpoint-disposition` "
             "answers nothing for them. Both halves of the seal go through that "
             "answer: the client sends the plaintext it is holding, and the "
             "server does not refuse it. Add the segment to `api-segment->table` "
             "if its rows are sealed, or to `clear-api-segment->table` if they "
             "are deliberately not."))))

(deftest a-route-classified-as-sealed-is-one-the-guard-can-resolve
  ;; `endpoint-disposition` is a superset question — the audit path needs to know
  ;; *clear* as well as *sealed*, and the guard does not. This holds the two
  ;; together: whenever the disposition says sealed, the guard's own resolution
  ;; chain finds the same table, so a route this control accepts is a route
  ;; `refusal-for` acts on.
  (doseq [{:keys [path]} @prose-routes]
    (let [d (rules/endpoint-disposition path)
          guard-table (or (rules/convert-target path) (rules/endpoint-table path))]
      (if (:sealed? d)
        (is (= guard-table (:table d))
            (str path " reads as sealed here but the guard resolves " guard-table))
        (is (nil? guard-table)
            (str path " reads as clear here but the guard would treat it as sealed"))))))

(deftest the-two-segment-maps-agree-with-the-table-inventory
  ;; The other end of the control, and the one that catches a segment *moved*
  ;; rather than deleted. Without it, relabelling `"meets"` as clear would empty
  ;; the guard for meets and satisfy the coverage test above, since the segment
  ;; would still be classified.
  (testing "every sealed segment names a table that actually has a sealed column"
    (doseq [[segment table] rules/api-segment->table]
      (is (contains? rules/sealed-columns table)
          (str "/" segment " claims to write " table ", which sealed-columns does not name"))))
  (testing "every clear segment names a table `clear-tables` calls clear"
    (doseq [[segment table] rules/clear-api-segment->table]
      (is (contains? rules/clear-tables table)
          (str "/" segment " is declared clear but " table " is not in clear-tables"))
      (is (not (contains? rules/sealed-columns table))
          (str "/" segment " is declared clear and " table " has a sealed column"))))
  (testing "and no segment is in both maps"
    (is (empty? (clojure.set/intersection (set (keys rules/api-segment->table))
                                          (set (keys rules/clear-api-segment->table)))))))
