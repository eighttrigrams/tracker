(ns et.tr.category-inheritance-coverage-test
  "Holds `db/categorizable-entities` — the list of Item kinds that can carry
  Categories — against the two things it is supposed to agree with, so that the
  next kind added cannot quietly go uncovered the way the Journal did.

  Why this test exists. The Category Groups have had a registry since
  073-unify-category-tables and every enumeration of them is generated from it.
  The Item *kinds* had no registry, so they went on being counted by hand — and
  were counted wrong twice in a row. `tracker-add-drops-workstream-and-asset.md`
  said the bug was in seven add paths; the review of the fix agreed it was seven;
  both had missed the Journal, whose add form sits under the same sidebar as the
  other seven and whose list is filtered by the same six filters. The order that
  described a forgot-a-site bug repeated it, by hand-counting the sites.

  So the three assertions below are the ones a hand count cannot make:

  1. the registry names exactly the kinds the routes expose a categorize
     endpoint for — a ninth kind cannot arrive without appearing here;
  2. every kind really does accept a Category of every Group and hand it back
     under that Group's key — a seventh Group reddens for all of them at once;
  3. every kind that inherits its add filters is exercised by a scenario in the
     e2e feature, and every kind that does not has a reason recorded."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [et.tr.db :as db]
            [et.tr.integration-helpers :refer [with-integration-db
                                               POST-json GET-json]]))

(use-fixtures :each with-integration-db)

;; ---------------------------------------------------------------------------
;; 1. The registry against the routes

(defn- categorize-route-segments
  "The URL segments that mount `POST \"/:id/categorize\"`, read out of the route
  table itself.

  Reading the source is the point rather than a shortcut: the routes are the
  authority on what can be categorized, and a test that re-listed the kinds in
  order to check the list of kinds would only be a third hand count. Compojure's
  `defroutes` compiles to a closure with nothing to introspect, so the file is
  where the contexts can still be seen."
  []
  (let [src (slurp (io/file "src/clj/et/tr/server.clj"))]
    (->> (str/split-lines src)
         (reduce (fn [{:keys [context] :as acc} line]
                   (cond
                     (re-find #"\(context \"/" line)
                     (assoc acc :context (second (re-find #"\(context \"/([^\"]+)\"" line)))

                     (re-find #"\(POST \"/:id/categorize\"" line)
                     (update acc :segments conj context)

                     :else acc))
                 {:context nil :segments []})
         :segments
         set)))

(deftest the-registry-names-every-categorizable-kind-the-api-exposes
  (testing "db/categorizable-entities and the categorize routes are the same set"
    (let [from-routes (categorize-route-segments)
          from-registry (set (map :segment db/categorizable-entities))]
      (is (seq from-routes) "found no categorize routes at all — the reader below is broken")
      (is (= from-routes from-registry)
          (str "a kind can be categorized but is not in db/categorizable-entities, "
               "or vice versa. Only in the routes: "
               (pr-str (sort (remove from-registry from-routes)))
               ". Only in the registry: "
               (pr-str (sort (remove from-routes from-registry))))))))

;; ---------------------------------------------------------------------------
;; 2. Every kind accepts every Group

(defn- create-item! [segment title]
  (:body (POST-json (str "/api/" segment) {:title title})))

(deftest every-kind-carries-a-category-from-every-group
  (testing "each categorizable kind accepts one Category per Group and reads it back under that Group's key"
    ;; Both loops are over registries, so neither the kinds nor the Groups are
    ;; named here: a seventh Group or a ninth kind is covered the day it is added.
    (doseq [{:keys [segment kind]} db/categorizable-entities]
      (let [item (create-item! segment (str "Coverage " (name kind)))
            expected (into {}
                           (for [{:keys [type key]} db/category-groups]
                             (let [c (:body (POST-json (str "/api/" (name key))
                                                       {:name (str "Cov-" (name kind) "-" type)}))]
                               (POST-json (str "/api/" segment "/" (:id item) "/categorize")
                                          {:category-type type :category-id (:id c)})
                               [key (:name c)])))
            reread (:body (GET-json (str "/api/" segment "/" (:id item))))]
        (is (some? (:id item)) (str "could not create a " segment " item"))
        (doseq [[key expected-name] expected]
          (is (= [expected-name] (mapv :name (get reread key)))
              (str segment " lost its " key " category")))))))

;; ---------------------------------------------------------------------------
;; 3. Every inheriting kind has an e2e scenario; every exempt kind has a reason

(def ^:private inheritance-feature "test/e2e/features/category-inheritance.feature")

(deftest every-inheriting-kind-is-exercised-by-the-e2e-feature
  (testing "each kind whose add path inherits the filters has a scenario naming its collection"
    (let [feature (slurp (io/file inheritance-feature))]
      (doseq [{:keys [segment kind inherits-add-filters?]} db/categorizable-entities
              :when inherits-add-filters?]
        (is (str/includes? feature (str "\"" segment "\""))
            (str "no scenario in " inheritance-feature " adds a " (name kind)
                 " under a filter — add one, or record why this kind does not "
                 "inherit its add filters in db/categorizable-entities"))))))

(deftest every-exempt-kind-says-why
  (testing "a kind that does not inherit its add filters has to give a reason"
    (doseq [{:keys [kind inherits-add-filters? why-not]} db/categorizable-entities
            :when (not inherits-add-filters?)]
      (is (and (string? why-not) (< 40 (count why-not)))
          (str (name kind) " is exempt from add-filter inheritance with no :why-not — "
               "an exemption without a reason is how the Journal went missing")))))
