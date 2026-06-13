(ns et.tr.mottos-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db.motto :as db.motto]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(defn- add!
  ([title] (add! title "" "both"))
  ([title scope] (add! title "" scope))
  ([title description scope]
   (db.motto/add-motto *ds* *user-id* title description scope)))

(deftest add-and-list-motto-test
  (testing "add motto returns row with id, title, description, scope"
    (let [m (add! "Carpe Diem" "Seize the day" "both")]
      (is (some? (:id m)))
      (is (= "Carpe Diem" (:title m)))
      (is (= "Seize the day" (:description m)))
      (is (= "both" (:scope m))))
    (let [all (db.motto/list-mottos *ds* *user-id*)]
      (is (= 1 (count all)))
      (is (= "Carpe Diem" (:title (first all)))))))

(deftest scope-defaults-to-both-when-invalid-test
  (testing "invalid scope normalizes to both"
    (let [m (add! "Foo" "" "bogus")]
      (is (= "both" (:scope m))))))

(deftest list-mottos-scope-filter-non-strict-test
  (testing "non-strict context private includes private and both"
    (add! "P" "private")
    (add! "B" "both")
    (add! "W" "work")
    (let [private-results (db.motto/list-mottos *ds* *user-id* {:context "private"})
          work-results (db.motto/list-mottos *ds* *user-id* {:context "work"})
          both-results (db.motto/list-mottos *ds* *user-id* {:context "both"})]
      (is (= #{"P" "B"} (set (map :title private-results))))
      (is (= #{"W" "B"} (set (map :title work-results))))
      (is (= #{"P" "B" "W"} (set (map :title both-results)))))))

(deftest list-mottos-scope-filter-strict-test
  (testing "strict context filters by exact scope"
    (add! "P" "private")
    (add! "B" "both")
    (add! "W" "work")
    (is (= #{"P"} (set (map :title (db.motto/list-mottos *ds* *user-id* {:context "private" :strict true})))))
    (is (= #{"W"} (set (map :title (db.motto/list-mottos *ds* *user-id* {:context "work" :strict true})))))
    (is (= #{"B"} (set (map :title (db.motto/list-mottos *ds* *user-id* {:context "both" :strict true})))))))

(deftest list-mottos-search-test
  (testing "search matches title and description"
    (add! "Carpe Diem" "Seize the day" "both")
    (add! "Memento Mori" "Remember death" "both")
    (let [by-title (db.motto/list-mottos *ds* *user-id* {:search-term "Carpe"})
          by-desc (db.motto/list-mottos *ds* *user-id* {:search-term "death"})
          no-hit (db.motto/list-mottos *ds* *user-id* {:search-term "Buddha"})]
      (is (= 1 (count by-title)))
      (is (= "Carpe Diem" (:title (first by-title))))
      (is (= 1 (count by-desc)))
      (is (= "Memento Mori" (:title (first by-desc))))
      (is (empty? no-hit)))))

(deftest update-motto-test
  (testing "update changes title and description"
    (let [{:keys [id]} (add! "Old title" "Old description" "private")
          updated (db.motto/update-motto *ds* *user-id* id
                                         {:title "New title"
                                          :description "New description"})]
      (is (= "New title" (:title updated)))
      (is (= "New description" (:description updated))))))

(deftest set-motto-scope-test
  (testing "scope is updated"
    (let [{:keys [id]} (add! "Foo" "" "both")
          updated (db.motto/set-motto-field *ds* *user-id* id :scope "work")]
      (is (= "work" (:scope updated))))))

(deftest motto-time-window-default-is-both-test
  (testing "newly added motto defaults to time_window = 'both'"
    (let [m (add! "T")]
      (is (= "both" (:time_window m))))))

(deftest set-motto-time-window-test
  (testing "time_window can be set to daytime / nighttime / both"
    (let [{:keys [id]} (add! "T")]
      (is (= "daytime"   (:time_window (db.motto/set-motto-field *ds* *user-id* id :time_window "daytime"))))
      (is (= "nighttime" (:time_window (db.motto/set-motto-field *ds* *user-id* id :time_window "nighttime"))))
      (is (= "both"      (:time_window (db.motto/set-motto-field *ds* *user-id* id :time_window "both"))))))
  (testing "invalid time_window normalizes to 'both'"
    (let [{:keys [id]} (add! "T2")
          result (db.motto/set-motto-field *ds* *user-id* id :time_window "bogus")]
      (is (= "both" (:time_window result))))))

(deftest delete-motto-test
  (testing "delete removes the row"
    (let [{:keys [id]} (add! "Bye")]
      (is (= {:success true} (db.motto/delete-motto *ds* *user-id* id)))
      (is (zero? (count (db.motto/list-mottos *ds* *user-id*))))))
  (testing "delete returns nil/false when row not owned"
    (is (nil? (db.motto/delete-motto *ds* *user-id* 99999)))))
