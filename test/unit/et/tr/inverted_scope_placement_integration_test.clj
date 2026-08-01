(ns et.tr.inverted-scope-placement-integration-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db.user :as db.user]
            [et.tr.integration-helpers :refer [*ds* *user-id* with-integration-db
                                               GET-json PUT-json]]))

(use-fixtures :each with-integration-db)

(defn- placement-of [username]
  (->> (GET-json "/api/auth/available-users")
       :body
       (filter #(= username (:username %)))
       first
       :inverted_scope_placement))

(deftest inverted-scope-placement-round-trip
  (testing "a fresh user has the setting off"
    (is (= 0 (:inverted_scope_placement (db.user/get-user-by-username *ds* "test-user"))))
    (is (= 0 (placement-of "test-user"))))

  (testing "the toggle endpoint stores it and echoes the new row"
    (let [{:keys [status body]} (PUT-json "/api/user/inverted-scope-placement"
                                          {:inverted_scope_placement 1})]
      (is (= 200 status))
      (is (= {:id *user-id* :inverted_scope_placement 1} body))))

  (testing "both user select lists carry it to the client"
    ;; The login/me shape and the dev user picker read through different
    ;; queries; a column missing from either never reaches the frontend.
    (is (= 1 (:inverted_scope_placement (db.user/get-user-by-username *ds* "test-user"))))
    (is (= 1 (placement-of "test-user"))))

  (testing "it can be switched back off"
    (is (= 200 (:status (PUT-json "/api/user/inverted-scope-placement"
                                   {:inverted_scope_placement 0}))))
    (is (= 0 (placement-of "test-user")))))

(deftest inverted-scope-placement-rejects-bad-input
  (doseq [value [2 -1 "1" true nil]]
    (testing (str "value " (pr-str value) " is refused")
      (is (= 400 (:status (PUT-json "/api/user/inverted-scope-placement"
                                     {:inverted_scope_placement value}))))))
  (is (= 0 (:inverted_scope_placement (db.user/get-user-by-username *ds* "test-user")))))
