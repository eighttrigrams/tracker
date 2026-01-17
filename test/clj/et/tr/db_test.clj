(ns et.tr.db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [et.tr.db :as db]))

(def ^:dynamic *ds* nil)

(defn with-in-memory-db [f]
  (let [db-name (str "file:test" (System/nanoTime) "?mode=memory&cache=shared")
        ds (jdbc/get-datasource {:dbtype "sqlite" :dbname db-name})
        conn (jdbc/get-connection ds)]
    (db/create-tables conn)
    (try
      (binding [*ds* conn]
        (f))
      (finally
        (.close conn)))))

(use-fixtures :each with-in-memory-db)

(deftest add-item-test
  (testing "adds item with title and returns it"
    (let [item (db/add-item *ds* "Test task")]
      (is (some? (:id item)))
      (is (= "Test task" (:title item)))
      (is (= "" (:description item)))
      (is (some? (:created_at item)))
      (is (some? (:sort_order item))))))

(deftest add-item-sort-order-test
  (testing "new items get decreasing sort_order (appear at top)"
    (let [item1 (db/add-item *ds* "First")
          item2 (db/add-item *ds* "Second")
          item3 (db/add-item *ds* "Third")]
      (is (< (:sort_order item3) (:sort_order item2)))
      (is (< (:sort_order item2) (:sort_order item1))))))

(deftest list-items-empty-test
  (testing "returns empty list when no items"
    (is (= [] (db/list-items *ds*)))))

(deftest list-items-with-tags-test
  (testing "returns items with tags"
    (let [item (db/add-item *ds* "Task with tags")
          person (db/add-person *ds* "Alice")]
      (db/tag-item *ds* (:id item) "person" (:id person))
      (let [items (db/list-items *ds*)
            retrieved (first items)]
        (is (= 1 (count items)))
        (is (= "Task with tags" (:title retrieved)))
        (is (= [{:id (:id person) :name "Alice"}] (:people retrieved)))
        (is (= [] (:places retrieved)))
        (is (= [] (:projects retrieved)))
        (is (= [] (:goals retrieved)))))))

(deftest list-items-recent-mode-test
  (testing "recent mode returns all items"
    (db/add-item *ds* "First")
    (db/add-item *ds* "Second")
    (db/add-item *ds* "Third")
    (let [items (db/list-items *ds* :recent)]
      (is (= 3 (count items)))
      (is (= #{"First" "Second" "Third"} (set (map :title items)))))))

(deftest list-items-manual-mode-test
  (testing "manual mode orders by sort_order ASC"
    (db/add-item *ds* "First")
    (Thread/sleep 10)
    (db/add-item *ds* "Second")
    (Thread/sleep 10)
    (db/add-item *ds* "Third")
    (let [items (db/list-items *ds* :manual)]
      (is (= ["Third" "Second" "First"] (map :title items))))))

(deftest reorder-item-updates-sort-order-test
  (testing "updates item sort_order"
    (let [item (db/add-item *ds* "Test")
          result (db/reorder-item *ds* (:id item) 99.5)]
      (is (= true (:success result)))
      (is (= 99.5 (:sort_order result)))
      (is (= 99.5 (db/get-item-sort-order *ds* (:id item)))))))

(deftest reorder-item-changes-position-test
  (testing "reordering changes position in manual mode"
    (let [item1 (db/add-item *ds* "A")
          _item2 (db/add-item *ds* "B")
          item3 (db/add-item *ds* "C")
          initial-order (map :title (db/list-items *ds* :manual))]
      (is (= ["C" "B" "A"] initial-order))
      (db/reorder-item *ds* (:id item1) (- (:sort_order item3) 0.5))
      (let [new-order (map :title (db/list-items *ds* :manual))]
        (is (= ["A" "C" "B"] new-order))))))

(deftest get-item-sort-order-test
  (testing "returns sort_order for item"
    (let [item (db/add-item *ds* "Test")]
      (is (= (:sort_order item) (db/get-item-sort-order *ds* (:id item))))))

  (testing "returns nil for non-existent item"
    (is (nil? (db/get-item-sort-order *ds* 99999)))))

(deftest update-item-test
  (testing "updates title and description"
    (let [item (db/add-item *ds* "Original")
          updated (db/update-item *ds* (:id item) "Updated" "New description")]
      (is (= "Updated" (:title updated)))
      (is (= "New description" (:description updated)))
      (is (= (:id item) (:id updated))))))

(deftest tag-item-test
  (testing "can tag item with person, place, project, goal"
    (let [item (db/add-item *ds* "Task")
          person (db/add-person *ds* "Bob")
          place (db/add-place *ds* "Office")
          project (db/add-project *ds* "Website")
          goal (db/add-goal *ds* "Launch")]
      (db/tag-item *ds* (:id item) "person" (:id person))
      (db/tag-item *ds* (:id item) "place" (:id place))
      (db/tag-item *ds* (:id item) "project" (:id project))
      (db/tag-item *ds* (:id item) "goal" (:id goal))
      (let [items (db/list-items *ds*)
            tagged (first items)]
        (is (= 1 (count (:people tagged))))
        (is (= 1 (count (:places tagged))))
        (is (= 1 (count (:projects tagged))))
        (is (= 1 (count (:goals tagged))))))))

(deftest untag-item-test
  (testing "can untag item"
    (let [item (db/add-item *ds* "Task")
          person (db/add-person *ds* "Carol")]
      (db/tag-item *ds* (:id item) "person" (:id person))
      (is (= 1 (count (:people (first (db/list-items *ds*))))))
      (db/untag-item *ds* (:id item) "person" (:id person))
      (is (= 0 (count (:people (first (db/list-items *ds*)))))))))

(deftest people-crud-test
  (testing "add and list people"
    (db/add-person *ds* "Alice")
    (db/add-person *ds* "Bob")
    (let [people (db/list-people *ds*)]
      (is (= 2 (count people)))
      (is (= ["Alice" "Bob"] (map :name people))))))

(deftest places-crud-test
  (testing "add and list places"
    (db/add-place *ds* "Home")
    (db/add-place *ds* "Work")
    (let [places (db/list-places *ds*)]
      (is (= 2 (count places)))
      (is (= ["Home" "Work"] (map :name places))))))

(deftest projects-crud-test
  (testing "add and list projects"
    (db/add-project *ds* "Alpha")
    (db/add-project *ds* "Beta")
    (let [projects (db/list-projects *ds*)]
      (is (= 2 (count projects)))
      (is (= ["Alpha" "Beta"] (map :name projects))))))

(deftest goals-crud-test
  (testing "add and list goals"
    (db/add-goal *ds* "Learn Clojure")
    (db/add-goal *ds* "Ship product")
    (let [goals (db/list-goals *ds*)]
      (is (= 2 (count goals)))
      (is (= ["Learn Clojure" "Ship product"] (map :name goals))))))

(deftest sort-order-midpoint-test
  (testing "midpoint insertion maintains order"
    (let [item1 (db/add-item *ds* "A")
          item2 (db/add-item *ds* "B")
          item3 (db/add-item *ds* "C")
          order1 (:sort_order item1)
          order2 (:sort_order item2)
          midpoint (/ (+ order1 order2) 2.0)]
      (db/reorder-item *ds* (:id item3) midpoint)
      (let [items (db/list-items *ds* :manual)]
        (is (= ["B" "C" "A"] (map :title items)))))))
