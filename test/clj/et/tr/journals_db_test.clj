(ns et.tr.journals-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db.journal :as db.journal]
            [et.tr.db.journal-entry :as db.journal-entry]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(deftest add-journal-test
  (testing "creates a daily journal with default scope"
    (let [journal (db.journal/add-journal *ds* *user-id* "Morning Pages")]
      (is (some? (:id journal)))
      (is (= "Morning Pages" (:title journal)))
      (is (= "both" (:scope journal)))
      (is (= "daily" (:schedule_type journal)))
      (is (= [] (:people journal)))))

  (testing "creates a weekly journal with specific scope"
    (let [journal (db.journal/add-journal *ds* *user-id* "Weekly Review" "private" "weekly")]
      (is (= "private" (:scope journal)))
      (is (= "weekly" (:schedule_type journal))))))

(deftest list-journals-test
  (testing "lists all journals for user"
    (db.journal/add-journal *ds* *user-id* "Journal A")
    (db.journal/add-journal *ds* *user-id* "Journal B")
    (let [journals (db.journal/list-journals *ds* *user-id*)]
      (is (= 2 (count journals)))))

  (testing "filters by scope"
    (db.journal/add-journal *ds* *user-id* "Private J" "private" "daily")
    (db.journal/add-journal *ds* *user-id* "Work J" "work" "daily")
    (let [work (db.journal/list-journals *ds* *user-id* {:context "work"})]
      (is (every? #(contains? #{"work" "both"} (:scope %)) work)))))

(deftest delete-journal-test
  (let [journal (db.journal/add-journal *ds* *user-id* "To Delete")]
    (testing "deletes a journal"
      (let [result (db.journal/delete-journal *ds* *user-id* (:id journal))]
        (is (:success result))))))

(deftest create-entry-for-journal-test
  (let [journal (db.journal/add-journal *ds* *user-id* "Daily Log")]
    (testing "creates an entry from journal template"
      (let [entry (db.journal/create-entry-for-journal *ds* *user-id* (:id journal) "2026-04-11")]
        (is (some? (:id entry)))
        (is (= "Daily Log" (:title entry)))
        (is (= "2026-04-11" (:entry_date entry)))
        (is (= (:id journal) (:journal_id entry)))))))

(deftest auto-create-journal-entries-test
  (testing "creates entries for journals that don't have one for today"
    (db.journal/add-journal *ds* *user-id* "Auto Daily" "both" "daily")
    (let [created (db.journal/auto-create-journal-entries *ds* *user-id*)]
      (is (= 1 (count created)))
      (is (= "Auto Daily" (:title (first created))))))

  (testing "does not duplicate entries"
    (let [created (db.journal/auto-create-journal-entries *ds* *user-id*)]
      (is (= 0 (count created))))))

(deftest journal-entry-crud-test
  (testing "add and list journal entries"
    (let [entry (db.journal-entry/add-journal-entry *ds* *user-id* "Standalone Entry" "both")]
      (is (some? (:id entry)))
      (is (= "Standalone Entry" (:title entry))))
    (let [entries (db.journal-entry/list-journal-entries *ds* *user-id*)]
      (is (pos? (count entries)))))

  (testing "update journal entry"
    (let [entry (db.journal-entry/add-journal-entry *ds* *user-id* "Original" "both")
          updated (db.journal-entry/update-journal-entry *ds* *user-id* (:id entry)
                    {:title "Updated" :description "notes" :tags "tag1"})]
      (is (= "Updated" (:title updated)))))

  (testing "delete journal entry"
    (let [entry (db.journal-entry/add-journal-entry *ds* *user-id* "To Remove" "both")
          result (db.journal-entry/delete-journal-entry *ds* *user-id* (:id entry))]
      (is (:success result)))))
