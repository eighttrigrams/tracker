(ns et.tr.top-of-order-integration-test
  "Every create path that puts a new row at the top of a manual ordering, held to
  that property directly.

  These exist because eight such paths each computed the position inline instead
  of calling `db/top-of-order`, and none of the four entities had a test saying
  where a new row lands — so the consolidation would have been unguarded. The
  risk being covered is regression and nothing else: a replacement that quietly
  changed the landing spot is the only way this refactor can be wrong, and
  before these tests nothing would have said so.

  Two things are deliberate in every test here, both taken from
  `et.tr.issue-convert-integration-test`, which is the one path converted in the
  earlier batch and therefore the reference:

  - **An existing row is pushed far down its own order first.** A newcomer is
    trivially \"first\" in a list of one, and a create path that copied a
    position or defaulted to zero would still pass such a test. Pushing the
    sitting row to 500.0 means only a value below it can come out on top.
  - **The position is read through the list function the page renders**, not off
    the column. That is the question actually being asked — where the row
    appears — and it keeps the test honest about ordering the listing applies on
    top of the column (issues sort `done` ahead of `sort_order`, for one)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.tr.db :as db]
            [et.tr.db.task :as db.task]
            [et.tr.db.issue :as db.issue]
            [et.tr.db.resource :as db.resource]
            [et.tr.db.journal :as db.journal]
            [et.tr.db.journal-entry :as db.journal-entry]
            [et.tr.db.recurring-task :as db.recurring-task]
            [et.tr.db.message :as db.message]
            [et.tr.integration-helpers :refer [with-integration-db *ds* *user-id*]]))

(use-fixtures :each with-integration-db)

(defn- push-down!
  "Send `id` to the bottom of `table`'s `col`, so that only a position computed
  below the existing minimum can come out first."
  [table col id]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:update table :set {col 500.0} :where [:= :id id]})))

(defn- manual-task-titles []
  (mapv :title (db.task/list-tasks *ds* *user-id* :manual)))

(defn- manual-issue-titles []
  (mapv :title (db.issue/list-issues *ds* *user-id* {:sort-mode "manual"})))

(defn- manual-resource-titles []
  (mapv :title (db.resource/list-resources *ds* *user-id* {:sort-mode "manual"})))

(defn- manual-entry-titles []
  (mapv :title (db.journal-entry/list-journal-entries *ds* *user-id* {:sort-mode "manual"})))

;; ---------------------------------------------------------------- tasks

(deftest a-new-task-lands-at-the-top-of-the-manual-order
  (testing "db.task/add-task"
    (let [sitting (db.task/add-task *ds* *user-id* "Already there")]
      (push-down! :tasks :sort_order (:id sitting))
      (let [fresh (db.task/add-task *ds* *user-id* "Newcomer")]
        (is (not= 500.0 (:sort_order fresh)))
        (is (= ["Newcomer" "Already there"] (manual-task-titles))
            "a new task goes to the top of the manual order")))))

(deftest a-task-converted-from-a-message-lands-at-the-top
  (testing "db.task/convert-message-to-task"
    (let [sitting (db.task/add-task *ds* *user-id* "Already there")
          message (db.message/add-message *ds* *user-id* "sender" "From a message" "" nil nil nil nil)]
      (push-down! :tasks :sort_order (:id sitting))
      (let [fresh (db.task/convert-message-to-task *ds* *user-id* (:id message))]
        (is (some? fresh) "the conversion happened at all")
        (is (= ["From a message" "Already there"] (manual-task-titles))
            "a task converted from a message goes to the top of the manual order")))))

(deftest a-task-created-for-a-recurring-task-lands-at-the-top
  (testing "db.recurring-task/create-task-for-recurring"
    (let [sitting (db.task/add-task *ds* *user-id* "Already there")
          rtask (db.recurring-task/add-recurring-task *ds* *user-id* "Every Tuesday")]
      (push-down! :tasks :sort_order (:id sitting))
      (let [fresh (db.recurring-task/create-task-for-recurring
                    *ds* *user-id* (:id rtask) "2026-07-15" "09:00")]
        (is (some? fresh) "the occurrence was created at all")
        (is (= ["Every Tuesday" "Already there"] (manual-task-titles))
            "an occurrence of a recurring task goes to the top of the manual order")))))

;; ---------------------------------------------------------------- issues

(deftest a-new-issue-lands-at-the-top-of-the-manual-order
  (testing "db.issue/add-issue"
    (let [sitting (db.issue/add-issue *ds* *user-id* "Already there")]
      (push-down! :issues :sort_order (:id sitting))
      (let [fresh (db.issue/add-issue *ds* *user-id* "Newcomer")]
        (is (not= 500.0 (:sort_order fresh)))
        (is (= ["Newcomer" "Already there"] (manual-issue-titles))
            "a new issue goes to the top of the manual order")))))

;; ---------------------------------------------------------------- resources

(deftest a-new-resource-lands-at-the-top-of-the-manual-order
  (testing "db.resource/add-resource"
    (let [sitting (db.resource/add-resource *ds* *user-id* "Already there" "http://a" "both")]
      (push-down! :resources :sort_order (:id sitting))
      (let [fresh (db.resource/add-resource *ds* *user-id* "Newcomer" "http://b" "both")]
        (is (not= 500.0 (:sort_order fresh)))
        (is (= ["Newcomer" "Already there"] (manual-resource-titles))
            "a new resource goes to the top of the manual order")))))

(deftest a-resource-converted-from-a-message-lands-at-the-top
  (testing "db.resource/convert-message-to-resource"
    (let [sitting (db.resource/add-resource *ds* *user-id* "Already there" "http://a" "both")
          message (db.message/add-message *ds* *user-id* "sender" "From a message" "" nil nil nil nil)]
      (push-down! :resources :sort_order (:id sitting))
      (let [fresh (db.resource/convert-message-to-resource *ds* *user-id* (:id message) "http://b")]
        (is (some? fresh) "the conversion happened at all")
        (is (= ["From a message" "Already there"] (manual-resource-titles))
            "a resource converted from a message goes to the top of the manual order")))))

;; ---------------------------------------------------------- journal entries

(deftest a-new-journal-entry-lands-at-the-top-of-the-manual-order
  (testing "db.journal-entry/add-journal-entry"
    (let [sitting (db.journal-entry/add-journal-entry *ds* *user-id* "Already there" "both")]
      (push-down! :journal_entries :sort_order (:id sitting))
      (let [fresh (db.journal-entry/add-journal-entry *ds* *user-id* "Newcomer" "both")]
        (is (not= 500.0 (:sort_order fresh)))
        (is (= ["Newcomer" "Already there"] (manual-entry-titles))
            "a new journal entry goes to the top of the manual order")))))

(deftest an-entry-created-for-a-journal-lands-at-the-top
  (testing "db.journal/create-entry-for-journal"
    (let [sitting (db.journal-entry/add-journal-entry *ds* *user-id* "Already there" "both")
          journal (db.journal/add-journal *ds* *user-id* "Morning pages")]
      (push-down! :journal_entries :sort_order (:id sitting))
      (let [fresh (db.journal/create-entry-for-journal
                    *ds* *user-id* (:id journal) "2026-07-15")]
        (is (some? fresh) "the entry was created at all")
        (is (= ["Morning pages" "Already there"] (manual-entry-titles))
            "an entry created for a journal goes to the top of the manual order")))))
