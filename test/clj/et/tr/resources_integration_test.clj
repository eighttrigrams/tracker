(ns et.tr.resources-integration-test
  (:require [clojure.test :refer :all]
            [et.tr.integration-helpers :refer :all]))

(use-fixtures :each with-integration-db)

(defn- add-ledger! [title]
  (:body (POST-json "/api/resources" {:title title})))

(defn- update-resource! [id fields]
  (PUT-json (str "/api/resources/" id) fields))

(defn- get-resource [id]
  (:body (GET-json (str "/api/resources/" id))))

(deftest update-ledger-description
  (let [ledger (add-ledger! "My Ledger")
        resp (update-resource! (:id ledger) {:title "My Ledger" :description "Some notes"})]
    (is (= 200 (:status resp)))
    (let [fetched (get-resource (:id ledger))]
      (is (= "Some notes" (:description fetched))))))

(deftest update-resource-with-link-description
  (let [resource (:body (POST-json "/api/resources" {:title "A Link" :link "https://example.com"}))
        resp (update-resource! (:id resource) {:title "A Link" :link "https://example.com" :description "Link notes"})]
    (is (= 200 (:status resp)))
    (let [fetched (get-resource (:id resource))]
      (is (= "Link notes" (:description fetched))))))
