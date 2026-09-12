(ns et.tr.seal-guard-integration-test
  "The server half of *the client seals, the server enforces*.

  The server holds no key and cannot read a body. What it can do is the one thing
  no client can: know **whose** rows these are. Tracker has three humans in one
  database and only one of them seals, so a client deciding that from a username
  would be a guess, and a guess that seals somebody else's prose is not
  recoverable.

  The rule under test, and the wording is the whole of it:

  > For a user whose `seal_prose` is 1, a write may not **introduce** new
  > plaintext prose into a sealed column. An **echo** of the value already stored
  > is not an introduction."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [et.tr.db :as db]
            [et.tr.envelope :as envelope]
            [et.tr.integration-helpers :refer [*ds* *user-id* GET-json POST-json PUT-json
                                               with-integration-db]]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]))

(use-fixtures :each with-integration-db)

(def ^:private fixture
  (delay (edn/read-string (slurp (io/file "test/fixtures/seal-vectors.edn")))))

(defn- an-envelope
  "A real ciphertext out of the shared fixture. The server cannot open it and
  does not try — what is under test is that it can tell one from prose."
  []
  (:sealed (first (:vectors @fixture))))

(defn- seal-user! [on?]
  (jdbc/execute-one! (db/get-conn *ds*)
                     (sql/format {:update :users
                                  :set {:seal_prose (if on? 1 0)}
                                  :where [:= :id *user-id*]})))

(defn- new-task! []
  (:id (:body (POST-json "/api/tasks" {:title "a task"}))))

(defn- stored-description [id]
  (:description (jdbc/execute-one! (db/get-conn *ds*)
                                   (sql/format {:select [:description] :from [:tasks]
                                                :where [:= :id id]})
                                   db/jdbc-opts)))

;; ---------------------------------------------------------------------------
;; The prefix is the one the clients use.

(deftest the-server-knows-the-same-prefix-the-clients-do
  (is (= (get-in @fixture [:envelope :prefix]) envelope/prefix))
  (is (true? (envelope/sealed? (an-envelope))))
  (doseq [p (:passthrough @fixture)]
    (is (false? (envelope/sealed? p)) (str p " must read as plaintext"))))

(deftest the-server-inventory-is-the-one-the-fixture-names
  (is (= (into {} (for [[t cs] (:sealed-columns @fixture)] [t (mapv keyword cs)]))
         envelope/sealed-columns))
  (is (not (envelope/sealed-table? :messages))
      "message bodies are written by three keyless producers and searched by body"))

;; ---------------------------------------------------------------------------
;; A user who does not seal is exactly what tracker was before any of this.

(deftest a-user-who-does-not-seal-writes-prose-freely
  (seal-user! false)
  (let [id (new-task!)
        resp (PUT-json (str "/api/tasks/" id) {:title "a task" :description "readable" :tags ""})]
    (is (= 200 (:status resp)))
    (is (= "readable" (stored-description id)))))

(deftest a-user-who-does-not-seal-may-not-be-handed-an-envelope
  (seal-user! false)
  (let [id (new-task!)
        resp (PUT-json (str "/api/tasks/" id) {:title "a task" :description (an-envelope) :tags ""})]
    (is (= 400 (:status resp)) "a keyed client must not seal somebody else's rows")
    (is (= "sealed" (get-in resp [:body :reason])))
    (is (= "" (stored-description id)) "and nothing was written")))

;; ---------------------------------------------------------------------------
;; A sealing user.

(deftest a-sealing-user-may-write-an-envelope
  (seal-user! true)
  (let [id (new-task!)
        resp (PUT-json (str "/api/tasks/" id) {:title "a task" :description (an-envelope) :tags ""})]
    (is (= 200 (:status resp)))
    (is (= (an-envelope) (stored-description id)))))

(deftest a-sealing-user-may-not-introduce-readable-text
  (seal-user! true)
  (let [id (new-task!)
        resp (PUT-json (str "/api/tasks/" id) {:title "a task" :description "readable" :tags ""})]
    (is (= 400 (:status resp)))
    (is (= "unsealed" (get-in resp [:body :reason])))
    (is (= "description" (get-in resp [:body :column])))
    (is (= "" (stored-description id)) "nothing written")))

(deftest a-blank-body-is-always-allowed
  (seal-user! true)
  (let [id (new-task!)]
    (testing "blank is never sealed, so a sealing user still writes one"
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id)
                                    {:title "a task" :description "" :tags ""}))))
      (is (= "" (stored-description id))))
    (testing "and whitespace-only counts as blank, as it does in the seal itself"
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id)
                                    {:title "a task" :description "  " :tags ""})))))))

(deftest an-echo-of-what-is-stored-is-not-an-introduction
  ;; This is the rule that keeps a half-migrated database usable. Before the
  ;; walker runs, every body is plaintext; a no-op save sends that plaintext
  ;; straight back, and refusing it would make every unmigrated row unsavable.
  (seal-user! false)
  (let [id (new-task!)]
    (PUT-json (str "/api/tasks/" id) {:title "a task" :description "not migrated yet" :tags ""})
    (seal-user! true)
    (let [resp (PUT-json (str "/api/tasks/" id)
                         {:title "a task" :description "not migrated yet" :tags ""})]
      (is (= 200 (:status resp)) "the same bytes that are already there")
      (is (= "not migrated yet" (stored-description id))))
    (testing "but changing it is an introduction, and refused"
      (is (= 400 (:status (PUT-json (str "/api/tasks/" id)
                                    {:title "a task" :description "edited in the clear" :tags ""}))))
      (is (= "not migrated yet" (stored-description id))))))

(deftest a-create-has-nothing-to-echo
  (seal-user! true)
  (let [resp (POST-json "/api/resources" {:title "r" :description "readable"})]
    (is (= 400 (:status resp))
        "no stored value can make this an echo, so it is an introduction")))

(deftest the-guard-covers-every-sealed-entity-and-not-messages
  (seal-user! true)
  (testing "an issue is guarded"
    (let [id (:id (:body (POST-json "/api/issues" {:title "an issue"})))]
      (is (= 400 (:status (PUT-json (str "/api/issues/" id)
                                    {:title "an issue" :description "readable" :tags ""}))))))
  (testing "a category is guarded, through whichever of its six URLs"
    (let [id (:id (:body (POST-json "/api/people" {:name "Andrew"})))]
      (is (= 400 (:status (PUT-json (str "/api/people/" id)
                                    {:name "Andrew" :description "readable" :tags ""}))))))
  (testing "a message is not"
    (let [id (:id (:body (POST-json "/api/messages" {:sender "a" :title "m"
                                                     :description "readable"})))]
      (is (some? id) "the inbox keeps filling, from three producers with no key"))))

(deftest a-write-that-carries-no-body-is-not-the-guard-s-business
  (seal-user! true)
  (let [id (new-task!)]
    (is (= 200 (:status (PUT-json (str "/api/tasks/" id) {:title "retitled" :tags "x"})))
        "an absent column is not a write to that column")))
