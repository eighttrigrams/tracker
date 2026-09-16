(ns et.tr.deliverable-estimate-integration-test
  "The two fields a task carries for planning rather than for prose: what it
  produces, and how long it is expected to take.

  Three things here are worth a test and the rest is plumbing.

  **The unit.** `1.2` is one hour and twelve minutes because the column is
  decimal hours. Every assertion about a number in this file is really an
  assertion about that sentence, since the alternative reading — `1.2` as one
  hour twenty — is just as plausible to somebody who has not been told.

  **The partial write.** These two are the first task fields that a `PUT
  /api/tasks/:id` may leave alone. Title, description and tags are written on
  every request because every caller sends all three; the inline title edit in
  the task list sends a body built from the row's own description and tags and
  knows nothing about a deliverable. If the handler defaulted an absent
  `:deliverable` to `\"\"` the way it defaults `:description`, renaming a task
  from the list would erase what it delivers, silently and with no way back. The
  tests that post a body *without* the keys are the ones guarding that.

  **The inheritance.** A recurring task is a template, so what it delivers and
  how long it takes belong to the tasks it creates."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.integration-helpers :refer [with-integration-db
                                               POST-json PUT-json GET-json]]
            [et.tr.server.common :as common]))

(use-fixtures :each with-integration-db)

(defn- new-task [title]
  (:body (POST-json "/api/tasks" {:title title})))

;; ---------------------------------------------------------------------------
;; The unit

(deftest parse-time-estimate-reads-decimal-hours-test
  (testing "1.2 is an hour and twelve minutes, not an hour and twenty"
    (is (= [:ok 1.2] (common/parse-time-estimate 1.2)))
    (is (= 72.0 (* 60 (second (common/parse-time-estimate 1.2))))
        "1.2 hours is 72 minutes"))
  (testing "a form posts a string and a machine user posts a number"
    (is (= [:ok 1.5] (common/parse-time-estimate "1.5")))
    (is (= [:ok 1.5] (common/parse-time-estimate 1.5)))
    (is (= [:ok 2.0] (common/parse-time-estimate "2"))))
  (testing "blank is no estimate, which is nil and not zero"
    (is (= [:ok nil] (common/parse-time-estimate nil)))
    (is (= [:ok nil] (common/parse-time-estimate "")))
    (is (= [:ok nil] (common/parse-time-estimate "   ")))
    (is (= [:ok 0.0] (common/parse-time-estimate 0))
        "an explicit zero is a claim and survives as one"))
  (testing "what will not parse is refused rather than rounded to something"
    (is (= :error (first (common/parse-time-estimate "1:12"))))
    (is (= :error (first (common/parse-time-estimate "an hour"))))
    (is (= :error (first (common/parse-time-estimate "-1"))))
    (is (= :error (first (common/parse-time-estimate -1))))
    (is (= :error (first (common/parse-time-estimate 99999))))
    (is (= :error (first (common/parse-time-estimate ##NaN))))
    (is (= :error (first (common/parse-time-estimate ##Inf))))))

;; ---------------------------------------------------------------------------
;; Tasks

(deftest task-defaults-to-no-deliverable-and-no-estimate-test
  (let [task (new-task "Fresh")]
    (is (= "" (:deliverable task)))
    (is (nil? (:time_estimate task))
        "no estimate is nil, so it stays distinguishable from an estimate of zero")))

(deftest task-round-trips-both-fields-test
  (let [task (new-task "Report")
        resp (PUT-json (str "/api/tasks/" (:id task))
                       {:title "Report" :description "" :tags ""
                        :deliverable "PDF, 10 pages" :time-estimate 1.2})]
    (is (= 200 (:status resp)))
    (is (= "PDF, 10 pages" (get-in resp [:body :deliverable])))
    (is (= 1.2 (get-in resp [:body :time_estimate])))
    (testing "and comes back on a re-read, not just in the write's response"
      (let [reread (:body (GET-json (str "/api/tasks/" (:id task))))]
        (is (= "PDF, 10 pages" (:deliverable reread)))
        (is (= 1.2 (:time_estimate reread)))))
    (testing "and appears in the listing, so the client's copy stays whole"
      (let [listed (->> (:body (GET-json "/api/tasks"))
                        (filter #(= (:id %) (:id task)))
                        first)]
        (is (= "PDF, 10 pages" (:deliverable listed)))
        (is (= 1.2 (:time_estimate listed)))))))

(deftest task-estimate-accepts-a-string-from-a-form-test
  (let [task (new-task "Typed")
        resp (PUT-json (str "/api/tasks/" (:id task))
                       {:title "Typed" :description "" :tags "" :time-estimate "2.5"})]
    (is (= 200 (:status resp)))
    (is (= 2.5 (get-in resp [:body :time_estimate])))))

(deftest task-estimate-can-be-cleared-test
  (let [task (new-task "Estimated")
        _ (PUT-json (str "/api/tasks/" (:id task))
                    {:title "Estimated" :description "" :tags "" :time-estimate 3})
        resp (PUT-json (str "/api/tasks/" (:id task))
                       {:title "Estimated" :description "" :tags "" :time-estimate nil})]
    (is (= 200 (:status resp)))
    (is (nil? (get-in resp [:body :time_estimate]))
        "an explicitly sent nil clears it — which is how the modal's empty field arrives")))

(deftest task-rejects-an-unparseable-estimate-test
  (let [task (new-task "Bad")
        resp (PUT-json (str "/api/tasks/" (:id task))
                       {:title "Bad" :description "" :tags "" :time-estimate "1:12"})]
    (is (= 400 (:status resp))
        "refused rather than quietly stored as nil, which would read as 'no estimate'")
    (testing "and the rest of the write is refused with it"
      (is (= "Bad" (:title (:body (GET-json (str "/api/tasks/" (:id task))))))))))

(deftest task-update-without-the-keys-leaves-them-alone-test
  (testing "the inline title edit sends title/description/tags and nothing else"
    (let [task (new-task "Write the report")
          _ (PUT-json (str "/api/tasks/" (:id task))
                      {:title "Write the report" :description "" :tags ""
                       :deliverable "PDF, 10 pages" :time-estimate 1.2})
          ;; Exactly the body `item-card/make-inline-edit` builds: the row's own
          ;; description and tags, a new title, no opinion about anything else.
          renamed (PUT-json (str "/api/tasks/" (:id task))
                            {:title "Write the Q3 report" :description "" :tags ""})]
      (is (= 200 (:status renamed)))
      (is (= "Write the Q3 report" (get-in renamed [:body :title])))
      (let [reread (:body (GET-json (str "/api/tasks/" (:id task))))]
        (is (= "PDF, 10 pages" (:deliverable reread))
            "a rename from the task list must not erase the deliverable")
        (is (= 1.2 (:time_estimate reread))
            "nor the estimate")))))

(deftest task-update-can-set-one-field-without-the-other-test
  (let [task (new-task "Half")
        _ (PUT-json (str "/api/tasks/" (:id task))
                    {:title "Half" :description "" :tags ""
                     :deliverable "A decision" :time-estimate 4})
        resp (PUT-json (str "/api/tasks/" (:id task))
                       {:title "Half" :description "" :tags "" :time-estimate 2})]
    (is (= 200 (:status resp)))
    (is (= 2.0 (get-in resp [:body :time_estimate])))
    (is (= "A decision" (:deliverable (:body (GET-json (str "/api/tasks/" (:id task))))))
        "the estimate moved and the deliverable did not")))

;; ---------------------------------------------------------------------------
;; Recurring tasks

(deftest recurring-task-round-trips-both-fields-test
  (let [rtask (:body (POST-json "/api/recurring-tasks" {:title "Weekly review"}))
        resp (PUT-json (str "/api/recurring-tasks/" (:id rtask))
                       {:title "Weekly review" :description "" :tags ""
                        :deliverable "Updated board" :time-estimate 0.5})]
    (is (= 200 (:status resp)))
    (is (= "Updated board" (get-in resp [:body :deliverable])))
    (is (= 0.5 (get-in resp [:body :time_estimate])))))

(deftest recurring-task-update-without-the-keys-leaves-them-alone-test
  (let [rtask (:body (POST-json "/api/recurring-tasks" {:title "Weekly review"}))
        _ (PUT-json (str "/api/recurring-tasks/" (:id rtask))
                    {:title "Weekly review" :description "" :tags ""
                     :deliverable "Updated board" :time-estimate 0.5})
        renamed (PUT-json (str "/api/recurring-tasks/" (:id rtask))
                          {:title "Weekly review, Mondays" :description "" :tags ""})]
    (is (= 200 (:status renamed)))
    (let [reread (->> (:body (GET-json "/api/recurring-tasks"))
                      (filter #(= (:id %) (:id rtask)))
                      first)]
      (is (= "Updated board" (:deliverable reread)))
      (is (= 0.5 (:time_estimate reread))))))

(deftest created-task-inherits-deliverable-and-estimate-test
  (testing "a recurring task is a template, so both fields ride along"
    (let [rtask (:body (POST-json "/api/recurring-tasks" {:title "Weekly review"}))
          _ (PUT-json (str "/api/recurring-tasks/" (:id rtask))
                      {:title "Weekly review" :description "" :tags ""
                       :deliverable "Updated board" :time-estimate 0.5})
          created (:body (POST-json (str "/api/recurring-tasks/" (:id rtask) "/create-task")
                                    {:date "2026-09-21"}))]
      (is (= "Updated board" (:deliverable created)))
      (is (= 0.5 (:time_estimate created)))
      (testing "copied and not joined, so editing the template leaves it alone"
        (PUT-json (str "/api/recurring-tasks/" (:id rtask))
                  {:title "Weekly review" :description "" :tags ""
                   :deliverable "Something else" :time-estimate 3})
        (is (= "Updated board"
               (:deliverable (:body (GET-json (str "/api/tasks/" (:id created)))))))))))

(deftest created-task-inherits-nothing-when-the-template-has-nothing-test
  (let [rtask (:body (POST-json "/api/recurring-tasks" {:title "Plain"}))
        created (:body (POST-json (str "/api/recurring-tasks/" (:id rtask) "/create-task")
                                  {:date "2026-09-21"}))]
    (is (= "" (:deliverable created)))
    (is (nil? (:time_estimate created)))))
