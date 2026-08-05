(ns et.tr.category-reorder-integration-test
  "POST /api/{people,places,workstreams,projects,goals,assets}/:id/reorder,
  through the real routing + handler stack.

  Since 073-unify-category-tables the six Groups are one table and one ordering
  context, so nothing structural keeps a reorder addressed to one group off
  another group's rows: before the unification the identical request compiled
  to `UPDATE projects ... WHERE id = <a person>`, which matched no rows and was
  a no-op by accident. What replaces that accident is the handler's own check
  that the subject is in the group whose ordering it was handed, and this is
  where that check is held.

  It is held here rather than at the db level because db/write-order! updates
  one row by id: a db-level test asserting that another group's rows did not
  move cannot fail whatever the handler does, so it would assert nothing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [et.tr.integration-helpers :refer [GET-json POST-json with-integration-db]]))

(use-fixtures :each with-integration-db)

(defn- create! [segment name]
  (:body (POST-json (str "/api/" segment "/") {:name name})))

(defn- orders
  "name -> sort_order for one Group. The stored value, not the position in the
  list: categories are served most-recently-modified first, so a sort_order
  that moved need not move a row in the list."
  [segment]
  (into {} (map (juxt :name :sort_order)) (:body (GET-json (str "/api/" segment "/")))))

(defn- reorder! [segment id target-id position]
  (POST-json (str "/api/" segment "/" id "/reorder")
             {:target-category-id target-id :position position}))

(deftest a-reorder-refuses-a-subject-from-another-group
  (let [p1 (create! "people" "P1")
        _p2 (create! "people" "P2")
        j1 (create! "projects" "J1")
        _j2 (create! "projects" "J2")
        people-before (orders "people")
        projects-before (orders "projects")]
    (testing "precondition: two Groups of two, each with its own positions"
      (is (= {"P1" 1.0 "P2" 2.0} people-before))
      (is (= {"J1" 1.0 "J2" 2.0} projects-before)))
    (testing "a person addressed as a project is refused"
      (let [{:keys [status body]} (reorder! "projects" (:id p1) (:id j1) "after")]
        (is (= 404 status))
        (is (false? (:success body)))
        (is (re-find #"not in this group" (str (:error body))))))
    (testing "and People did not move: unguarded, P1 takes a position computed
              between two projects and the owner's People list silently
              reshuffles"
      (is (= people-before (orders "people"))))
    (testing "nor did Projects"
      (is (= projects-before (orders "projects"))))))

(deftest a-reorder-within-the-group-still-writes-the-computed-position
  (testing "the positive control for the refusal above: the same endpoint, the
            same two rows, the subject in the group this time -- so the refusal
            is the guard doing its work and not the endpoint being inert"
    (let [p1 (create! "people" "P1")
          p2 (create! "people" "P2")
          before (orders "people")
          {:keys [status body]} (reorder! "people" (:id p1) (:id p2) "after")]
      (is (= 200 status))
      (is (true? (:success body)))
      (is (not= (get before "P1") (:sort_order body))
          "the endpoint must write a position P1 did not already hold")
      (is (= (:sort_order body) (get (orders "people") "P1")))
      (is (= (get before "P2") (get (orders "people") "P2"))
          "and must move only its subject"))))
