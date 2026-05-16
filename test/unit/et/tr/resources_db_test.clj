(ns et.tr.resources-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db.resource :as db.resource]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(defn- add! [title link]
  (db.resource/add-resource *ds* *user-id* title link "both"))

(deftest list-resources-domain-filter-test
  (testing "filters resources by domain (includes www variant)"
    (add! "A" "https://example.com/a")
    (add! "B" "https://www.example.com/b")
    (add! "C" "https://other.com/c")
    (let [filtered (db.resource/list-resources *ds* *user-id* {:domain "example.com"})]
      (is (= 2 (count filtered)))
      (is (every? #(re-find #"example\.com" (:link %)) filtered)))))

(deftest list-resources-excluded-domains-test
  (testing "excludes resources by single domain"
    (add! "A" "https://example.com/a")
    (add! "B" "https://www.example.com/b")
    (add! "C" "https://other.com/c")
    (let [filtered (db.resource/list-resources *ds* *user-id* {:excluded-domains #{"example.com"}})]
      (is (= 1 (count filtered)))
      (is (= "C" (:title (first filtered))))))

  (testing "excludes multiple domains"
    (let [filtered (db.resource/list-resources *ds* *user-id* {:excluded-domains #{"example.com" "other.com"}})]
      (is (= 0 (count filtered))))))

(deftest list-resources-excluded-sheet-test
  (testing "excluding Sheet hides resources with no link"
    (add! "Sheet1" nil)
    (add! "Sheet2" "")
    (add! "Linked" "https://example.com/x")
    (let [filtered (db.resource/list-resources *ds* *user-id* {:excluded-domains #{"Sheet"}})]
      (is (= 1 (count filtered)))
      (is (= "Linked" (:title (first filtered)))))))

(deftest list-resources-excluded-real-domain-keeps-sheets-test
  (testing "excluding a real domain does not hide linkless (Sheet) resources"
    (add! "Sheet1" nil)
    (add! "Example" "https://example.com/a")
    (add! "Other" "https://other.com/b")
    (let [filtered (db.resource/list-resources *ds* *user-id* {:excluded-domains #{"example.com"}})
          titles (set (map :title filtered))]
      (is (= 2 (count filtered)))
      (is (= #{"Sheet1" "Other"} titles)))))
