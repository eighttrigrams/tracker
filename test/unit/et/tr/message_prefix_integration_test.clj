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

(deftest description-no-longer-becomes-the-task-description-because-it-stops-the-task
  ;; **This test used to assert the opposite of its own new name**, and it is
  ;; rewritten rather than deleted because it is the one assertion in the file
  ;; that recorded the old behaviour: `T buy bread` plus a body produced a task
  ;; whose description was that body. That is precisely the write this change
  ;; exists to prevent — a body copied into a sealed column by a server that
  ;; holds no key, with no clear original beside it.
  ;;
  ;; Deleting it would have removed the only place a reader could see the
  ;; behaviour change. A rewritten assertion keeps the shape of the old question
  ;; and answers it the new way.
  (let [resp (POST-message {:sender "alice"
                            :title "T buy bread"
                            :description "from the bakery on Main St"})]
    (is (= 201 (:status resp)))
    (is (empty? (filter #(= "buy bread" (:title %))
                        (db.task/list-tasks *ds* *user-id* :recent nil)))
        "no task carries that body any more")
    (is (contains? (message-titles) "T buy bread")
        "the body kept it in the Inbox, where its prose is expected to be clear")))

;; ---------------------------------------------------------------------------
;; The shortcut is a **title-only gesture**, so a message with a body is a
;; message.
;;
;; Three callers reach `task-prefix-match` and only one of them means to. The
;; human's own gesture — Telegram — posts `{:sender :title}` and **never carries a
;; body at all**. The other two pass somebody else's text through verbatim: the
;; mail poller sends the subject with the mail body beneath it, and the source
;; worker sends a feed item's title with the feed's body. Neither made the
;; gesture, and a YouTube video called *"T Rex documentary"* was silently
;; becoming a task instead of arriving in the Inbox.
;;
;; The body is the discriminator, and it needs no sender check, no credential and
;; no allowlist — which is the point, because every discriminator of that kind is
;; the staleness this codebase has been caught by twice.

(deftest the-gesture-carries-no-body-and-still-becomes-a-task
  (testing "Telegram posts sender and title and nothing else"
    (let [resp (POST-message {:sender "Telegram" :title "t buy milk"})]
      (is (= 201 (:status resp)))
      (is (contains? (task-titles) "buy milk"))
      (is (not (contains? (message-titles) "t buy milk")))))
  (testing "an empty body is no body"
    (is (= 201 (:status (POST-message {:sender "Telegram" :title "t take the bins out"
                                       :description ""}))))
    (is (contains? (task-titles) "take the bins out")))
  (testing "and so is whitespace, which the task writer would have skipped anyway"
    (is (= 201 (:status (POST-message {:sender "Telegram" :title "t call the dentist"
                                       :description "   "}))))
    (is (contains? (task-titles) "call the dentist")))
  (testing "tt still reaches the today board"
    (is (= 201 (:status (POST-message {:sender "Telegram" :title "tt water plants"}))))
    (is (contains? (today-titles) "water plants"))))

(deftest a-mail-whose-subject-begins-like-the-shortcut-is-still-a-message
  ;; The poller sends the subject as the title and `From: … \n\n <body>` as the
  ;; description. A subject that happens to start `t ` is not a gesture.
  (let [resp (POST-message {:sender "mail" :title "t Rex documentary"
                            :description "From: nobody@example.com\n\nHave a look at this."})]
    (is (= 201 (:status resp)))
    (is (not (contains? (task-titles) "Rex documentary"))
        "nobody asked for a task, and the body would have gone with it")
    (is (contains? (message-titles) "t Rex documentary")
        "it lands in the Inbox, subject intact, to be read or converted there")))

(deftest a-feed-item-titled-like-the-shortcut-is-still-a-message
  ;; The source worker passes a feed item's title through verbatim. The feed
  ;; author never made the gesture and cannot be asked to avoid it.
  (let [resp (POST-message {:sender "YouTube" :title "T Rex documentary"
                            :description "https://example.com/watch?v=abc"})]
    (is (= 201 (:status resp)))
    (is (not (contains? (task-titles) "Rex documentary")))
    (is (contains? (message-titles) "T Rex documentary")))
  (testing "tt as well, which a title like \"tt the sequel\" would otherwise hit"
    (let [resp (POST-message {:sender "blog" :title "tt the sequel"
                              :description "a paragraph of somebody else's prose"})]
      (is (= 201 (:status resp)))
      (is (not (contains? (task-titles) "the sequel")))
      (is (contains? (message-titles) "tt the sequel")))))

(deftest a-body-is-what-makes-a-message-a-message-whoever-sent-it
  ;; No sender check anywhere: the same title from the same sender goes both ways
  ;; on the body alone, which is what makes this rule impossible to get stale.
  (is (= 201 (:status (POST-message {:sender "Telegram" :title "t read the docs"}))))
  (is (contains? (task-titles) "read the docs"))
  (is (= 201 (:status (POST-message {:sender "Telegram" :title "t read the docs"
                                     :description "chapter four especially"}))))
  (is (contains? (message-titles) "t read the docs")
      "same sender, same title, and the body is the whole of the difference"))


(deftest scope-is-preserved-on-the-created-task
  (let [resp (POST-message {:sender "alice" :title "t standup notes" :scope "work"})]
    (is (= 201 (:status resp)))
    (let [task (first (filter #(= "standup notes" (:title %))
                              (db.task/list-tasks *ds* *user-id* :recent nil)))]
      (is (= "work" (:scope task))))))


(deftest a-scalar-description-that-is-not-a-string-is-still-just-a-message
  ;; `validate-message-fields` checks sender, title, type, scope, importance and
  ;; urgency — and **not** description's type. So a numeric or boolean
  ;; description reaches `title-only-gesture`, and asking `str/blank?` about it
  ;; throws `ClassCastException`. My first version of this change did exactly
  ;; that: a 500 smuggled in behind a fix, found by a neighbouring agent reading
  ;; the diff and reproduced here before it was believed.
  ;;
  ;; The rule is *does this message carry a body*, and a number is a body, so the
  ;; gesture does not fire and the request goes where it always went.
  ;;
  ;; **Scalars only, and deliberately.** A *map* or vector description fails
  ;; deeper down, in honeysql, and that is **pre-existing and nothing to do with
  ;; this change** — measured, by posting one with an ordinary title that never
  ;; reaches the gesture at all: `These SQL clauses are unknown or have nil
  ;; values: :a`. Asserting it here would claim a fix that was not made.
  (doseq [odd [5 true]]
    (let [resp (POST-message {:sender "alice" :title "t buy bread" :description odd})]
      (is (= 201 (:status resp)) (str "description " (pr-str odd) " must not be a 500"))
      (is (contains? (message-titles) "t buy bread")
          (str "description " (pr-str odd) " is a body, so no task"))
      (is (empty? (filter #(= "buy bread" (:title %))
                          (db.task/list-tasks *ds* *user-id* :recent nil))))))) 
