(ns et.tr.oc-handler-integration-test
  "Handler-level optimistic-concurrency regression coverage for every editable
  type that carries a modified_at OC guard. For each type a real request goes
  through the routing + handler + DB stack (via the integration-helpers app)
  and asserts the four contract points that lock the feature down:

    (a) a stale expected-modified-at         -> 409 carrying the current row in :current
    (b) a matching expected-modified-at      -> 200 and the update applies
    (c) no expected-modified-at              -> 200 (last-write-wins for bot/API)
    (d) update of a nonexistent/deleted row  -> 404 with no :current

  Timestamps are deterministic: the created row's modified_at is set to a fixed
  known value with set-modified-at! before the assertions, so the matching case
  compares against a value we control and the tests never depend on wall-clock
  timing or hit the 1-second modified_at resolution."
  (:require [clojure.test :refer :all]
            [et.tr.integration-helpers :refer :all]
            [et.tr.db :as db]
            [et.tr.server.common :as common]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]))

(use-fixtures :each with-integration-db)

(def ^:private known-ts "2020-06-01 12:00:00")
(def ^:private stale-ts "1999-01-01 00:00:00")

(defn- set-modified-at!
  "Pin a row's modified_at to a known value so OC assertions are deterministic."
  [table id ts]
  (jdbc/execute-one! (db/get-conn @common/ds)
    (sql/format {:update table :set {:modified_at ts} :where [:= :id id]})))

(defn- run-oc-suite
  "Drive the four OC contract assertions against one type.
   Spec keys:
     :label       description string for the outer testing block
     :table       keyword table name (for set-modified-at!)
     :create      thunk returning the id of a freshly-created row
     :url         (fn [id] -> PUT url string)
     :name-key    body key carrying the editable name/title (:title or :name)
     :read-field  response key to verify the applied value (:title or :name)
     :base-body   extra always-valid body fields for the update"
  [{:keys [label table create url name-key read-field base-body]}]
  (testing label
    (let [id (create)
          new-val (str "oc-updated-" (name name-key))
          body (fn [extra] (merge base-body {name-key new-val} extra))]
      (set-modified-at! table id known-ts)
      (testing "(a) stale expected-modified-at -> 409 with current row, losing edit not applied"
        (let [{:keys [status body]} (PUT-json (url id) (body {:expected-modified-at stale-ts}))]
          (is (= 409 status))
          (is (some? (:current body)) "409 must carry the current row")
          (is (not= new-val (read-field (:current body))) "the losing edit must not be applied")))
      (testing "(b) matching expected-modified-at -> 200 and update applies"
        (let [{:keys [status body]} (PUT-json (url id) (body {:expected-modified-at known-ts}))]
          (is (= 200 status))
          (is (= new-val (read-field body)))))
      (testing "(c) no expected-modified-at -> 200 (last-write-wins preserved for bot/API)"
        (let [{:keys [status body]} (PUT-json (url id) (body {}))]
          (is (= 200 status))
          (is (= new-val (read-field body)))))
      (testing "(d) update of a nonexistent row -> 404, no :current"
        (let [{:keys [status body]} (PUT-json (url 999999) (body {:expected-modified-at known-ts}))]
          (is (= 404 status))
          (is (nil? (:current body))))))))

(deftest meet-optimistic-concurrency
  (run-oc-suite
    {:label "meet update honours the modified_at OC guard"
     :table :meets
     :create (fn [] (:id (:body (POST-json "/api/meets/" {:title "orig"}))))
     :url (fn [id] (str "/api/meets/" id))
     :name-key :title :read-field :title
     :base-body {:description "d" :tags "t"}}))

(deftest journal-optimistic-concurrency
  (run-oc-suite
    {:label "journal update honours the modified_at OC guard"
     :table :journals
     :create (fn [] (:id (:body (POST-json "/api/journals/" {:title "orig"}))))
     :url (fn [id] (str "/api/journals/" id))
     :name-key :title :read-field :title
     :base-body {:description "d" :tags "t"}}))

(deftest journal-entry-optimistic-concurrency
  (run-oc-suite
    {:label "journal-entry update honours the modified_at OC guard"
     :table :journal_entries
     :create (fn [] (:id (:body (POST-json "/api/journal-entries/" {:title "orig"}))))
     :url (fn [id] (str "/api/journal-entries/" id))
     :name-key :title :read-field :title
     :base-body {:description "d" :tags "t"}}))

(deftest resource-optimistic-concurrency
  (run-oc-suite
    {:label "resource update honours the modified_at OC guard"
     :table :resources
     :create (fn [] (:id (:body (POST-json "/api/resources/" {:title "orig"}))))
     :url (fn [id] (str "/api/resources/" id))
     :name-key :title :read-field :title
     :base-body {:description "d" :tags "t"}}))

(deftest category-person-optimistic-concurrency
  (run-oc-suite
    {:label "person update honours the modified_at OC guard"
     :table :people
     :create (fn [] (:id (:body (POST-json "/api/people/" {:name "orig"}))))
     :url (fn [id] (str "/api/people/" id))
     :name-key :name :read-field :name
     :base-body {:description "d" :tags "t"}}))

(deftest category-place-optimistic-concurrency
  (run-oc-suite
    {:label "place update honours the modified_at OC guard"
     :table :places
     :create (fn [] (:id (:body (POST-json "/api/places/" {:name "orig"}))))
     :url (fn [id] (str "/api/places/" id))
     :name-key :name :read-field :name
     :base-body {:description "d" :tags "t"}}))

(deftest category-project-optimistic-concurrency
  (run-oc-suite
    {:label "project update honours the modified_at OC guard"
     :table :projects
     :create (fn [] (:id (:body (POST-json "/api/projects/" {:name "orig"}))))
     :url (fn [id] (str "/api/projects/" id))
     :name-key :name :read-field :name
     :base-body {:description "d" :tags "t"}}))

(deftest category-goal-optimistic-concurrency
  (run-oc-suite
    {:label "goal update honours the modified_at OC guard"
     :table :goals
     :create (fn [] (:id (:body (POST-json "/api/goals/" {:name "orig"}))))
     :url (fn [id] (str "/api/goals/" id))
     :name-key :name :read-field :name
     :base-body {:description "d" :tags "t"}}))

(deftest motto-optimistic-concurrency
  (run-oc-suite
    {:label "motto update honours the modified_at OC guard"
     :table :mottos
     :create (fn [] (:id (:body (POST-json "/api/mottos/" {:title "orig"}))))
     :url (fn [id] (str "/api/mottos/" id))
     :name-key :title :read-field :title
     :base-body {:description "d"}}))

(deftest message-optimistic-concurrency
  (run-oc-suite
    {:label "message update honours the modified_at OC guard"
     :table :messages
     :create (fn [] (:id (:body (POST-json "/api/messages/" {:sender "s" :title "orig"}))))
     :url (fn [id] (str "/api/messages/" id))
     :name-key :title :read-field :title
     :base-body {:description "d"}}))
