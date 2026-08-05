(ns et.tr.ordering-isolation-test
  "Walks et.tr.ordering/contexts and holds every pair of contexts apart, in both
  directions: writing a position in one must leave every other registered column
  byte-identical. Wire a new context to a column another one already owns — the
  mistake the day list and Urgent Matters each made — and this fails.

  Three kinds of write assign a position, and each is walked separately: the
  explicit reorder, the placement an item gets when it *enters* a context, and
  the clear it gets when it leaves. Covering only the reorders would miss half
  the writers, which is how a mis-wired placement could still slip through."
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
  "Two rows in every table the registry names, members of nothing optional yet."
  []
  {:tasks (create! "/api/tasks" ["Task A" "Task B"] :title)
   :issues (create! "/api/issues" ["Issue A" "Issue B"] :title)
   :resources (create! "/api/resources" ["Resource A" "Resource B"] :title)
   :journal-entries (create! "/api/journal-entries" ["Entry A" "Entry B"] :title)
   ;; All six Category Groups are one table and one ordering context now, so
   ;; the registry has one entry to probe rather than four. Two rows in the
   ;; same group, because a reorder is only ever computed within a group --
   ;; that per-group isolation is guarded by
   ;; et.tr.category-reorder-integration-test, which this registry can no
   ;; longer express.
   :categories (create! "/api/people" ["Person A" "Person B"] :name)})

(defn- join-all!
  "Make both rows of every table a member of every context that table carries,
  so the reorder and leave probes have something to act on."
  [{:keys [tasks issues]}]
  (doseq [id tasks]
    (PUT-json (str "/api/tasks/" id "/today") {:today true})
    (PUT-json (str "/api/tasks/" id "/urgency") {:urgency "urgent"}))
  (doseq [id issues]
    (PUT-json (str "/api/issues/" id "/urgency") {:urgency "urgent"})))

;; One entry per context, and every entry answers all three questions. A context
;; that cannot be joined or left after creation says so with a keyword rather
;; than by omission — see every-context-is-probed.
;;
;;   :reorder — move the first fixture row past the second.
;;   :join    — make the first row a member, or :at-creation.
;;   :leave   — take the first row out again, or :with-the-row when the only way
;;              out is deleting it.
;;
;; A probe is the function to run, or {:setup :act} when the row has to be put
;; into some state first — `setup` runs before the columns are seeded, so what it
;; writes is not mistaken for what the probe wrote.
(def ^:private probes
  {:tasks-page
   {:reorder (fn [{:keys [tasks]}]
               (POST-json (str "/api/tasks/" (first tasks) "/reorder")
                          {:target-task-id (second tasks) :position "after"}))
    :join :at-creation
    :leave :with-the-row}

   :tasks-day-list
   {:reorder (fn [{:keys [tasks]}]
               (POST-json (str "/api/tasks/" (first tasks) "/reorder-today")
                          {:date (clock/today-str) :target-type "task"
                           :target-id (second tasks) :position "after"}))
    :join (fn [{:keys [tasks]}]
            (PUT-json (str "/api/tasks/" (first tasks) "/today") {:today true}))
    :leave (fn [{:keys [tasks]}]
             (PUT-json (str "/api/tasks/" (first tasks) "/today") {:today false}))}

   :tasks-urgent
   ;; The reorder names the block the row is already in, so it is a move and not
   ;; a join; the join names a different one, from a task that is on a day list,
   ;; because shedding the day is part of entering Urgent Matters.
   {:reorder (fn [{:keys [tasks]}]
               (POST-json (str "/api/tasks/" (first tasks) "/reorder-urgent")
                          {:urgency "urgent" :target-task-id (second tasks) :position "after"}))
    :join {:setup (fn [{:keys [tasks]}]
                    (PUT-json (str "/api/tasks/" (first tasks) "/today") {:today true}))
           :act (fn [{:keys [tasks]}]
                  (POST-json (str "/api/tasks/" (first tasks) "/reorder-urgent")
                             {:urgency "urgent"}))}
    :leave (fn [{:keys [tasks]}]
             (PUT-json (str "/api/tasks/" (first tasks) "/urgency") {:urgency "default"}))}

   :issues-page
   {:reorder (fn [{:keys [issues]}]
               (POST-json (str "/api/issues/" (first issues) "/reorder")
                          {:target-issue-id (second issues) :position "after"}))
    :join :at-creation
    :leave :with-the-row}

   :issues-urgent
   {:reorder (fn [{:keys [issues]}]
               (POST-json (str "/api/issues/" (first issues) "/reorder-urgent")
                          {:urgency "urgent" :target-issue-id (second issues) :position "after"}))
    :join (fn [{:keys [issues]}]
            (PUT-json (str "/api/issues/" (first issues) "/urgency") {:urgency "urgent"}))
    :leave (fn [{:keys [issues]}]
             (PUT-json (str "/api/issues/" (first issues) "/urgency") {:urgency "default"}))}

   :resources-page
   {:reorder (fn [{:keys [resources]}]
               (POST-json (str "/api/resources/" (first resources) "/reorder")
                          {:target-resource-id (second resources) :position "after"}))
    :join :at-creation
    :leave :with-the-row}

   :journal-entries
   {:reorder (fn [{:keys [journal-entries]}]
               (POST-json (str "/api/journal-entries/" (first journal-entries) "/reorder")
                          {:target-entry-id (second journal-entries) :position "after"}))
    :join :at-creation
    :leave :with-the-row}

   :categories
   {:reorder (fn [{:keys [categories]}]
               (POST-json (str "/api/people/" (first categories) "/reorder")
                          {:target-category-id (second categories) :position "after"}))
    :join :at-creation
    :leave :with-the-row}})

(def ^:private also-clears
  "The one write that legitimately reaches a second context, declared rather than
  arranged away: a task *entering* Urgent Matters comes off the day list, because
  the two are alternatives and not both, so the day position goes with the
  membership. Moving a card inside the block it is already in is not entering,
  and is held to the plain rule. Anything a probe touches beyond what is listed
  here is a leak, and what is listed here still has to have been cleared, not
  set."
  {[:tasks-urgent :join] #{:tasks-day-list}})

(def ^:private rows-key
  "Which fixture rows live in each context's table."
  {:tasks :tasks :issues :issues :resources :resources :journal_entries :journal-entries
   :categories :categories})

(defn- seed-columns!
  "Give every registered column on every fixture row a distinct known value, so
  a write to the wrong one cannot pass for the value that was already there."
  [rows]
  (doseq [[i [_ {:keys [table col]}]] (map-indexed vector ordering/contexts)
          [j id] (map-indexed vector (get rows (rows-key table)))]
    (jdbc/execute-one! (db/get-conn *ds*)
      (sql/format {:update table
                   :set {col (+ 100.0 (* 10 i) j)}
                   :where [:= :id id]}))))

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

(defn- probe-parts
  "A probe is a function, or {:setup :act} when the row needs putting into some
  state first."
  [spec]
  (if (map? spec) spec {:act spec}))

(defn- runnable? [spec]
  (fn? (:act (probe-parts spec))))

(defn- walk-probes!
  "Run every context's `kind` probe against freshly seeded columns and hand the
  caller what changed, so each assertion says only what it is about."
  [rows kind check!]
  (doseq [[context probe] probes
          :let [{:keys [setup act!]} (let [{:keys [setup act]} (probe-parts (get probe kind))]
                                       {:setup setup :act! act})]
          :when (fn? act!)]
    (when setup (setup rows))
    (seed-columns! rows)
    (let [before (snapshot rows)
          resp (act! rows)
          after (snapshot rows)
          touched (first (get rows (rows-key (ordering/table context))))
          shed (get also-clears [context kind] #{})
          changed (into {} (remove (fn [[k v]] (= v (get before k))) after))]
      (is (= 200 (:status resp))
          (str context " " (name kind) " failed: " (pr-str resp)))
      (is (= (into #{[context touched]} (map #(vector % touched)) shed)
             (set (keys changed)))
          (str context " " (name kind) " wrote outside its own column: "
               (pr-str (mapv (fn [[k v]] [k (get before k) '-> v]) changed))))
      (doseq [other shed]
        (is (nil? (get changed [other touched]))
            (str context " " (name kind) " set " other " rather than clearing it")))
      (check! context (get changed [context touched])))))

(deftest every-context-is-probed
  (testing "a context nobody knows how to reorder is a context this test misses"
    (is (= (set (keys ordering/contexts)) (set (keys probes)))))

  (testing "and every context answers for all three kinds of position write"
    (doseq [[context probe] probes]
      (is (= #{:reorder :join :leave} (set (keys probe))) (str context " is under-probed"))
      (is (runnable? (:reorder probe)) (str context " has no reorder probe"))
      (is (or (runnable? (:join probe)) (= :at-creation (:join probe)))
          (str context " has no join probe and does not say it is placed at creation"))
      (is (or (runnable? (:leave probe)) (= :with-the-row (:leave probe)))
          (str context " has no leave probe and does not say it is left only by deletion")))))

(deftest every-column-belongs-to-exactly-one-context
  (testing "two contexts sharing a table and a column is the coupling itself"
    (let [owned (map (juxt :table :col) (vals ordering/contexts))]
      (is (= (count owned) (count (distinct owned)))))))

(deftest reordering-in-one-context-leaves-every-other-column-untouched
  (let [rows (fixture-rows)]
    (join-all! rows)
    (walk-probes! rows :reorder
                  (fn [context value]
                    (is (some? value) (str context " reorder wrote nothing"))))))

(deftest joining-a-context-writes-only-its-own-column
  (testing "the placement an item gets on entering a context is a position write too"
    (let [rows (fixture-rows)]
      (walk-probes! rows :join
                    (fn [context value]
                      (is (some? value) (str context " join left no position")))))))

(deftest leaving-a-context-clears-only-its-own-column
  (testing "and so is the clear it gets on the way out"
    (let [rows (fixture-rows)]
      (join-all! rows)
      (walk-probes! rows :leave
                    (fn [context value]
                      (is (nil? value) (str context " leave did not clear its position")))))))
