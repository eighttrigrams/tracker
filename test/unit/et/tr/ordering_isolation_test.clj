(ns et.tr.ordering-isolation-test
  "Walks et.tr.ordering/contexts and holds every pair of contexts apart, in both
  directions: reordering in one must leave every other registered column
  byte-identical. Wire a new context to a column another one already owns — the
  mistake the day list and Urgent Matters each made — and this fails."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.tr.clock :as clock]
            [et.tr.db :as db]
            [et.tr.integration-helpers :refer [*ds* POST-json PUT-json with-integration-db]]
            [et.tr.ordering :as ordering]))

(use-fixtures :each with-integration-db)

(defn- create! [path titles title-key]
  (mapv (fn [title] (:id (:body (POST-json path {title-key title})))) titles))

(defn- fixture-rows
  "Two rows in every table the registry names, each a member of every context
  that table carries: on today's day list, and urgent."
  []
  (let [tasks (create! "/api/tasks" ["Task A" "Task B"] :title)
        issues (create! "/api/issues" ["Issue A" "Issue B"] :title)]
    (doseq [id tasks]
      (PUT-json (str "/api/tasks/" id "/today") {:today true})
      (PUT-json (str "/api/tasks/" id "/urgency") {:urgency "urgent"}))
    (doseq [id issues]
      (PUT-json (str "/api/issues/" id "/urgency") {:urgency "urgent"}))
    {:tasks tasks
     :issues issues
     :resources (create! "/api/resources" ["Resource A" "Resource B"] :title)
     :journal-entries (create! "/api/journal-entries" ["Entry A" "Entry B"] :title)
     :people (create! "/api/people" ["Person A" "Person B"] :name)
     :places (create! "/api/places" ["Place A" "Place B"] :name)
     :projects (create! "/api/projects" ["Project A" "Project B"] :name)
     :goals (create! "/api/goals" ["Goal A" "Goal B"] :name)}))

;; One probe per context: how to move its first fixture row past its second. A
;; context with no probe is a context this test does not cover, so the map has
;; to stay in step with the registry — see every-context-is-probed.
(def ^:private probes
  {:tasks-page
   (fn [{:keys [tasks]}]
     (POST-json (str "/api/tasks/" (first tasks) "/reorder")
                {:target-task-id (second tasks) :position "after"}))

   :tasks-day-list
   (fn [{:keys [tasks]}]
     (POST-json (str "/api/tasks/" (first tasks) "/reorder-today")
                {:date (clock/today-str) :target-type "task"
                 :target-id (second tasks) :position "after"}))

   :tasks-urgent
   (fn [{:keys [tasks]}]
     (POST-json (str "/api/tasks/" (first tasks) "/reorder-urgent")
                {:target-task-id (second tasks) :position "after"}))

   :issues-page
   (fn [{:keys [issues]}]
     (POST-json (str "/api/issues/" (first issues) "/reorder")
                {:target-issue-id (second issues) :position "after"}))

   :issues-urgent
   (fn [{:keys [issues]}]
     (POST-json (str "/api/issues/" (first issues) "/reorder-urgent")
                {:target-issue-id (second issues) :position "after"}))

   :resources-page
   (fn [{:keys [resources]}]
     (POST-json (str "/api/resources/" (first resources) "/reorder")
                {:target-resource-id (second resources) :position "after"}))

   :journal-entries
   (fn [{:keys [journal-entries]}]
     (POST-json (str "/api/journal-entries/" (first journal-entries) "/reorder")
                {:target-entry-id (second journal-entries) :position "after"}))

   :people
   (fn [{:keys [people]}]
     (POST-json (str "/api/people/" (first people) "/reorder")
                {:target-category-id (second people) :position "after"}))

   :places
   (fn [{:keys [places]}]
     (POST-json (str "/api/places/" (first places) "/reorder")
                {:target-category-id (second places) :position "after"}))

   :projects
   (fn [{:keys [projects]}]
     (POST-json (str "/api/projects/" (first projects) "/reorder")
                {:target-category-id (second projects) :position "after"}))

   :goals
   (fn [{:keys [goals]}]
     (POST-json (str "/api/goals/" (first goals) "/reorder")
                {:target-category-id (second goals) :position "after"}))})

(def ^:private rows-key
  "Which fixture rows live in each context's table."
  {:tasks :tasks :issues :issues :resources :resources :journal_entries :journal-entries
   :people :people :places :places :projects :projects :goals :goals})

(defn- seed-columns!
  "Give every registered column on every fixture row a distinct known value, so
  a write to the wrong one cannot pass for the value that was already there."
  [rows]
  (doseq [[i [context {:keys [table col]}]] (map-indexed vector ordering/contexts)
          [j id] (map-indexed vector (get rows (rows-key table)))]
    (jdbc/execute-one! (db/get-conn *ds*)
      (sql/format {:update table
                   :set {col (+ 100.0 (* 10 i) j)}
                   :where [:= :id id]}))
    (is (some? context))))

(defn- snapshot
  "Every registered column of every fixture row, keyed by [context id]."
  [rows]
  (into {}
        (for [[context {:keys [table col]}] ordering/contexts
              id (get rows (rows-key table))]
          [[context id]
           (get (jdbc/execute-one! (db/get-conn *ds*)
                  (sql/format {:select [col] :from [table] :where [:= :id id]})
                  db/jdbc-opts)
                col)])))

(deftest every-context-is-probed
  (testing "a context nobody knows how to reorder is a context this test misses"
    (is (= (set (keys ordering/contexts)) (set (keys probes))))))

(deftest every-column-belongs-to-exactly-one-context
  (testing "two contexts sharing a table and a column is the coupling itself"
    (let [owned (map (juxt :table :col) (vals ordering/contexts))]
      (is (= (count owned) (count (distinct owned)))))))

(deftest reordering-in-one-context-leaves-every-other-column-untouched
  (let [rows (fixture-rows)]
    (doseq [[context reorder!] probes]
      (seed-columns! rows)
      (let [before (snapshot rows)
            resp (reorder! rows)
            after (snapshot rows)
            moved (first (get rows (rows-key (ordering/table context))))
            changed (into {} (remove (fn [[k v]] (= v (get before k))) after))]
        (is (= 200 (:status resp)) (str context " reorder failed: " (pr-str resp)))
        (is (= #{[context moved]} (set (keys changed)))
            (str context " wrote outside its own column: "
                 (pr-str (mapv (fn [[k v]] [k (get before k) '-> v]) changed))))))))
