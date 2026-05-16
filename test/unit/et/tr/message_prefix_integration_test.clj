(ns et.tr.message-prefix-integration-test
  "Integration coverage for the t/tt shortcut prefix on POST /api/messages.
  When a posted title starts with `t<ws>` or `tt<ws>` (case-insensitive),
  the server skips the messages table and creates a task instead — `tt`
  also flips the today flag. The conversion runs inside the gate-exempt
  /api/messages endpoint so machine users hit it without recording mode."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [ring.mock.request :as mock]
            [et.tr.db.message :as db.message]
            [et.tr.db.task :as db.task]
            [et.tr.integration-helpers :refer [with-integration-db *app* *ds* *user-id*]]))

(use-fixtures :each with-integration-db)

(defn- POST-message [body]
  (let [resp (*app* (-> (mock/request :post "/api/messages")
                        (mock/header "X-User-Id" (str *user-id*))
                        (mock/header "Content-Type" "application/json")
                        (mock/body (json/write-str body))))]
    (update resp :body #(when (seq %) (json/read-str % :key-fn keyword)))))

(defn- task-titles [] (set (map :title (db.task/list-tasks *ds* *user-id* :recent nil))))
(defn- today-titles [] (set (map :title (db.task/list-tasks *ds* *user-id* :today nil))))
(defn- message-titles [] (set (map :title (db.message/list-messages *ds* *user-id*))))

(deftest no-prefix-creates-a-message
  (let [resp (POST-message {:sender "alice" :title "Hello" :description "body"})]
    (is (= 201 (:status resp)))
    (is (contains? (message-titles) "Hello"))
    (is (not (contains? (task-titles) "Hello")))))

(deftest lowercase-t-prefix-creates-a-task
  (let [resp (POST-message {:sender "alice" :title "t buy bread"})]
    (is (= 201 (:status resp)))
    (is (contains? (task-titles) "buy bread"))
    (is (not (contains? (today-titles) "buy bread")))
    (is (not (contains? (message-titles) "t buy bread")))))

(deftest uppercase-t-prefix-creates-a-task
  (let [resp (POST-message {:sender "alice" :title "T buy bread"})]
    (is (= 201 (:status resp)))
    (is (contains? (task-titles) "buy bread"))))

(deftest tt-prefix-creates-task-on-today-board
  (let [resp (POST-message {:sender "alice" :title "tt water plants"})]
    (is (= 201 (:status resp)))
    (is (contains? (today-titles) "water plants"))))

(deftest uppercase-tt-prefix-creates-task-on-today-board
  (let [resp (POST-message {:sender "alice" :title "TT water plants"})]
    (is (= 201 (:status resp)))
    (is (contains? (today-titles) "water plants"))))

(deftest mixed-case-tt-prefix-still-matches
  (let [resp (POST-message {:sender "alice" :title "Tt water plants"})]
    (is (= 201 (:status resp)))
    (is (contains? (today-titles) "water plants"))))

(deftest multiple-whitespace-after-t-is-trimmed
  (let [resp (POST-message {:sender "alice" :title "t   buy   bread"})]
    (is (= 201 (:status resp)))
    (is (contains? (task-titles) "buy   bread"))
    (is (not (some #(re-find #"^t " %) (task-titles))))))

(deftest tab-after-t-counts-as-whitespace
  (let [resp (POST-message {:sender "alice" :title "t\tbuy bread"})]
    (is (= 201 (:status resp)))
    (is (contains? (task-titles) "buy bread"))))

(deftest bare-t-without-body-is-still-a-message
  (testing "no body → no task created, falls through to message"
    (let [resp (POST-message {:sender "alice" :title "t " :description "x"})]
      (is (= 201 (:status resp)))
      (is (contains? (message-titles) "t "))
      (is (empty? (task-titles))))))

(deftest title-starting-with-tea-is-not-a-task
  (testing "no whitespace boundary → not the t prefix"
    (let [resp (POST-message {:sender "alice" :title "team meeting"})]
      (is (= 201 (:status resp)))
      (is (contains? (message-titles) "team meeting"))
      (is (empty? (task-titles))))))

(deftest description-becomes-the-task-description
  (let [resp (POST-message {:sender "alice"
                            :title "T buy bread"
                            :description "from the bakery on Main St"})]
    (is (= 201 (:status resp)))
    (let [task (first (filter #(= "buy bread" (:title %))
                              (db.task/list-tasks *ds* *user-id* :recent nil)))]
      (is (some? task))
      (is (= "from the bakery on Main St" (:description task))))))

(deftest scope-is-preserved-on-the-created-task
  (let [resp (POST-message {:sender "alice" :title "t standup notes" :scope "work"})]
    (is (= 201 (:status resp)))
    (let [task (first (filter #(= "standup notes" (:title %))
                              (db.task/list-tasks *ds* *user-id* :recent nil)))]
      (is (= "work" (:scope task))))))
