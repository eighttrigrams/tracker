(ns et.tr.message-categories-integration-test
  "Categories on messages — the ninth kind, and the one that had none.

  `category-inheritance-coverage-test` already holds messages against the two
  registries and proves a message takes a Category of every Group, because it
  loops over `db/categorizable-entities` and messages are now in it. That is the
  generic half and it needs nothing here.

  What this file covers is the half that is particular to the Inbox: the sidebar
  narrowing the message list, which is the whole reason the human asked, and the
  two places a join table for messages can go wrong that the others cannot —
  `messages` is deleted rather than archived, and its rows are written by
  producers that hold no key and know nothing about Categories."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [et.tr.integration-helpers :refer [with-integration-db
                                               POST-json PUT-json GET-json DELETE-json]]
            [et.tr.db :as db]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.tr.server.common :as common]))

(use-fixtures :each with-integration-db)

(defn- new-message [title]
  (:body (POST-json "/api/messages" {:sender "Note" :title title})))

(defn- new-workstream [name]
  (:body (POST-json "/api/workstreams" {:name name})))

(defn- categorize! [message-id category]
  (POST-json (str "/api/messages/" (:id message-id) "/categorize")
             {:category-type (:category_type category) :category-id (:id category)}))

(defn- inbox
  ([] (inbox nil))
  ([query] (:body (GET-json (str "/api/messages?view=inbox" (when query (str "&" query)))))))

(defn- titles [messages]
  (set (map :title messages)))

(defn- links
  "The join rows, read straight out of the table. The API cannot answer these:
  a cascade leaving a row behind is invisible through an endpoint that only ever
  joins back to a message or a category that is gone."
  []
  (jdbc/execute! (db/get-conn (common/ensure-ds))
    (sql/format {:select [:message_id :category_type :category_id]
                 :from [:message_categories]})
    db/jdbc-opts))

(defn- link-count [] (count (links)))

(defn- link-types [] (mapv :category_type (links)))

;; ---------------------------------------------------------------------------
;; The sidebar narrowing the list — the ask

(deftest the-sidebar-selection-narrows-the-inbox-test
  (let [ws (new-workstream "Plurama")
        filed (new-message "Filed under Plurama")
        _ (new-message "Filed under nothing")]
    (categorize! filed ws)
    (testing "unfiltered, both are in the Inbox"
      (is (= #{"Filed under Plurama" "Filed under nothing"} (titles (inbox)))))
    (testing "filtered by the Group, only the message carrying it"
      (is (= #{"Filed under Plurama"} (titles (inbox "workstreams=Plurama")))))
    (testing "a Group with nothing selected narrows nothing"
      (is (= 2 (count (inbox "people=")))))
    (testing "excluding it drops it instead"
      (is (= #{"Filed under nothing"} (titles (inbox "excluded-workstreams=Plurama")))))))

(deftest a-message-carries-its-categories-into-the-listing-and-the-detail-test
  (let [ws (new-workstream "Plurama")
        message (new-message "Carries it")]
    (categorize! message ws)
    (testing "the listing"
      (is (= ["Plurama"] (mapv :name (:workstreams (first (inbox)))))))
    (testing "and the single-message read, which is a different query"
      (is (= ["Plurama"]
             (mapv :name (:workstreams (:body (GET-json (str "/api/messages/" (:id message)))))))))))

(deftest a-fresh-message-carries-every-group-key-empty-test
  (testing "the shape a listed message has, so the client's copy of a just-added
            one is not the single row missing its Group keys"
    (let [message (new-message "Fresh")]
      (doseq [{:keys [key]} db/category-groups]
        (is (contains? message key) (str "a new message has no " key " key"))
        (is (= [] (get message key)))))))

;; ---------------------------------------------------------------------------
;; The two things only messages do

(deftest deleting-a-message-takes-its-category-links-with-it-test
  (testing "the declared ON DELETE CASCADE does not do this — `PRAGMA
            foreign_keys` is off, so `db.message/drop-category-links!` is what
            does, and it matters here because messages are deleted rather than
            archived"
    (let [ws (new-workstream "Plurama")
          message (new-message "Doomed")]
      (categorize! message ws)
      (is (= 1 (link-count)) "nothing to clean up — the test is not testing anything")
      (DELETE-json (str "/api/messages/" (:id message)))
      (is (zero? (link-count))
          "the message is gone and its category links are not"))))

(deftest converting-hands-the-categories-to-what-replaces-the-message-test
  (testing "to a task"
    (let [ws (new-workstream "Plurama")
          message (new-message "Becomes a task")]
      (categorize! message ws)
      (let [task (:body (POST-json (str "/api/messages/" (:id message) "/convert-to-task") {}))]
        (is (= ["Plurama"] (mapv :name (:workstreams task)))
            "the conversion's own response should already say so")
        (is (= ["Plurama"]
               (mapv :name (:workstreams (:body (GET-json (str "/api/tasks/" (:id task)))))))
            "and the stored task should carry it")
        (is (zero? (link-count))
            "the links moved rather than being copied — the message is gone"))))
  ;; A second Group of its own: both blocks share one database — the fixture is
  ;; per-deftest — and a duplicate name in one Group is refused, which would
  ;; leave this block categorizing nothing and asserting it.
  (testing "and to a resource"
    (let [ws (new-workstream "Rhizome")
          message (new-message "Becomes a resource")]
      (categorize! message ws)
      (let [resource (:body (POST-json (str "/api/messages/" (:id message) "/convert-to-resource")
                                       {:link "https://example.com/x"}))]
        (is (= ["Rhizome"] (mapv :name (:workstreams resource))))
        (is (zero? (link-count)))))))

(deftest deleting-a-category-takes-its-message-links-with-it-test
  (testing "message_categories is in delete-category's cleanup, unlike the five
            join tables left in the documented pre-existing gap: this table
            shipped empty, so starting it correct changes nobody's data"
    (let [ws (new-workstream "Plurama")
          message (new-message "Filed")]
      (categorize! message ws)
      (is (= 1 (link-count)))
      (DELETE-json (str "/api/workstreams/" (:id ws)))
      (is (zero? (link-count))
          "the category is gone and its message links are not"))))

(deftest moving-a-category-between-groups-keeps-the-message-mirror-in-step-test
  (testing "message_categories.category_type is the same denormalised mirror the
            other eight carry, and `join-tables` is what makes the group move
            visit it"
    (let [ws (new-workstream "Plurama")
          message (new-message "Filed")]
      (categorize! message ws)
      (PUT-json (str "/api/categories/" (:id ws) "/group") {:group "project"})
      (is (= ["project"] (link-types))
          "the mirror still says workstream while the category says project")
      (testing "and the message is found under its new Group"
        (is (= #{"Filed"} (titles (inbox "projects=Plurama"))))))))
