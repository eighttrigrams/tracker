(ns et.tr.messages-integration-test
  (:require [clojure.test :refer :all]
            [et.tr.integration-helpers :refer :all]
            [et.tr.db :as db]
            [et.tr.db.user :as db.user]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]))

(use-fixtures :each with-integration-db)

(defn- add-message! [title]
  (:body (POST-json "/api/messages" {:sender "Note" :title title})))

(defn- archive-message! [id]
  (PUT-json (str "/api/messages/" id "/done") {:done true}))

(defn- list-messages [sort-mode]
  (:body (GET-json (str "/api/messages?sort=" sort-mode))))

(deftest delete-all-archived-removes-only-archived-messages
  (let [m1 (add-message! "Active one")
        m2 (add-message! "To archive")
        m3 (add-message! "Also archive")]
    (archive-message! (:id m2))
    (archive-message! (:id m3))

    (is (= 1 (count (list-messages "recent"))))
    (is (= 2 (count (list-messages "done"))))

    (let [resp (DELETE-json "/api/messages/archived")]
      (is (= 200 (:status resp)))
      (is (= 2 (:deleted-count (:body resp)))))

    (is (= 1 (count (list-messages "recent"))))
    (is (= 0 (count (list-messages "done"))))
    (is (= "Active one" (:title (first (list-messages "recent")))))))

(deftest delete-all-archived-does-not-affect-other-users
  (let [m1 (add-message! "My archived")]
    (archive-message! (:id m1))

    (let [other-user (db.user/create-user *ds* "other-user" "pass123")]
      (jdbc/execute-one! (db/get-conn *ds*)
        (sql/format {:update :users :set {:has_mail 1} :where [:= :id (:id other-user)]}))
      (binding [*user-id* (:id other-user)]
        (let [resp (DELETE-json "/api/messages/archived")]
          (is (= 200 (:status resp)))
          (is (= 0 (:deleted-count (:body resp)))))))

    (is (= 1 (count (list-messages "done"))))))

(deftest delete-all-archived-with-no-archived-messages
  (add-message! "Active message")
  (let [resp (DELETE-json "/api/messages/archived")]
    (is (= 200 (:status resp)))
    (is (= 0 (:deleted-count (:body resp)))))
  (is (= 1 (count (list-messages "recent")))))
