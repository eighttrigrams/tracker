(ns et.tr.events-prune-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.tr.db :as db]
            [et.tr.db.user :as db.user]
            [et.tr.db.event :as db.event]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(defn- insert-event! [effective-user-id ts]
  (:id (jdbc/execute-one! (db/get-conn *ds*)
         (sql/format {:insert-into :events
                      :values [{:ts ts
                                :actor_username "tester"
                                :effective_user_id effective-user-id
                                :action "update"
                                :payload "{}"}]
                      :returning [:id]})
         db/jdbc-opts)))

(defn- exists? [id]
  (some? (jdbc/execute-one! (db/get-conn *ds*)
           (sql/format {:select [:id] :from [:events] :where [:= :id id]})
           db/jdbc-opts)))

(deftest prune-deletes-events-older-than-two-months
  (let [old-id (insert-event! *user-id* "2020-01-01 00:00:00")
        recent-id (insert-event! *user-id* [:raw "datetime('now')"])]
    (db.event/prune-events! *ds* *user-id*)
    (testing "events older than 2 months are deleted"
      (is (not (exists? old-id))))
    (testing "recent events are kept"
      (is (exists? recent-id)))))

(deftest prune-caps-to-newest-events-per-user
  (let [ids (mapv (fn [n] (insert-event! *user-id* (str "2026-06-0" n " 00:00:00")))
                  [1 2 3 4 5])]
    (db.event/prune-events! *ds* *user-id* 3)
    (testing "only the newest cap events are kept"
      (is (not (exists? (nth ids 0))))
      (is (not (exists? (nth ids 1))))
      (is (exists? (nth ids 2)))
      (is (exists? (nth ids 3)))
      (is (exists? (nth ids 4))))))

(deftest prune-leaves-other-users-untouched
  (let [other (db.user/create-user *ds* "other-user" "pw")
        mine (insert-event! *user-id* "2020-01-01 00:00:00")
        theirs (insert-event! (:id other) "2020-01-01 00:00:00")]
    (db.event/prune-events! *ds* *user-id*)
    (testing "another user's events are not touched"
      (is (not (exists? mine)))
      (is (exists? theirs)))))

(deftest prune-system-events-by-cutoff-only
  (let [old-sys (insert-event! nil "2020-01-01 00:00:00")
        recent-sys (insert-event! nil [:raw "datetime('now')"])]
    (db.event/prune-system-events! *ds*)
    (testing "old NULL-effective events are pruned by cutoff"
      (is (not (exists? old-sys))))
    (testing "recent NULL-effective events are kept (no per-user cap applies)"
      (is (exists? recent-sys)))))

(deftest prune-system-events-not-capped
  (let [ids (mapv (fn [n] (insert-event! nil (str "2026-06-0" n " 00:00:00")))
                  [1 2 3 4 5])]
    (db.event/prune-system-events! *ds*)
    (testing "recent NULL-effective events are all kept regardless of count"
      (is (every? exists? ids)))))
