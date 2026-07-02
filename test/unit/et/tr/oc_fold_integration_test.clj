(ns et.tr.oc-fold-integration-test
  (:require [clojure.test :refer :all]
            [et.tr.integration-helpers :refer :all]))

(use-fixtures :each with-integration-db)

(deftest recurring-task-schedule-folds-into-content-update
  (testing "one OC-guarded PUT carries title + schedule, so a title-only-style save never self-conflicts"
    (let [created (:body (POST-json "/api/recurring-tasks/" {:title "Weekly review"}))
          id (:id created)
          m0 (:modified_at (:body (GET-json (str "/api/recurring-tasks/" id))))]
      (testing "content + schedule land in a single write and bump modified_at once"
        (let [{:keys [status body]} (PUT-json (str "/api/recurring-tasks/" id)
                                      {:title "Weekly review"
                                       :description "d" :tags "t"
                                       :expected-modified-at m0
                                       :schedule-days "1,3" :schedule-time "09:00"
                                       :schedule-mode "weekly" :biweekly-offset false
                                       :task-type "due_date"})]
          (is (= 200 status))
          (is (= "Weekly review" (:title body)))
          (is (= "1,3" (:schedule_days body)))
          (is (= "09:00" (:schedule_time body)))))
      (testing "repeated saves feeding the freshly-returned modified_at never spuriously 409 and keep the title"
        (loop [i 0
               expected (:modified_at (:body (GET-json (str "/api/recurring-tasks/" id))))]
          (when (< i 5)
            (let [{:keys [status body]} (PUT-json (str "/api/recurring-tasks/" id)
                                          {:title (str "Title " i)
                                           :description "d" :tags "t"
                                           :expected-modified-at expected
                                           :schedule-days "1,3" :schedule-time "09:00"
                                           :schedule-mode "weekly" :biweekly-offset false
                                           :task-type "due_date"})]
              (is (= 200 status) (str "iteration " i " must not self-conflict"))
              (is (= (str "Title " i) (:title body)) (str "iteration " i " must persist the title"))
              (recur (inc i) (:modified_at body)))))))))

(deftest recurring-task-stale-expected-still-conflicts
  (testing "a genuinely stale expected-modified-at is still rejected with 409 + current row"
    (let [id (:id (:body (POST-json "/api/recurring-tasks/" {:title "R"})))
          {:keys [status body]} (PUT-json (str "/api/recurring-tasks/" id)
                                  {:title "loser" :description "" :tags ""
                                   :expected-modified-at "1999-01-01 00:00:00"
                                   :schedule-days "" :schedule-time nil
                                   :schedule-mode "weekly" :biweekly-offset false
                                   :task-type "due_date"})]
      (is (= 409 status))
      (is (= "R" (:title (:current body)))))))

(deftest meeting-series-schedule-folds-into-content-update
  (testing "one OC-guarded PUT carries title + schedule for a meeting series"
    (let [id (:id (:body (POST-json "/api/meeting-series/" {:title "Sync"})))
          m0 (:modified_at (:body (GET-json (str "/api/meeting-series/" id))))
          {:keys [status body]} (PUT-json (str "/api/meeting-series/" id)
                                  {:title "Sync renamed" :description "" :tags ""
                                   :expected-modified-at m0
                                   :schedule-days "2,4" :schedule-time "10:00"
                                   :schedule-mode "weekly" :biweekly-offset false :maybe "0"})]
      (is (= 200 status))
      (is (= "Sync renamed" (:title body)))
      (is (= "2,4" (:schedule_days body)))
      (is (= "10:00" (:schedule_time body))))))

(deftest meeting-series-stale-expected-still-conflicts
  (testing "a genuinely stale expected-modified-at is still rejected with 409 + current row"
    (let [id (:id (:body (POST-json "/api/meeting-series/" {:title "S"})))
          {:keys [status body]} (PUT-json (str "/api/meeting-series/" id)
                                  {:title "loser" :description "" :tags ""
                                   :expected-modified-at "1999-01-01 00:00:00"
                                   :schedule-days "" :schedule-time nil
                                   :schedule-mode "weekly" :biweekly-offset false :maybe "0"})]
      (is (= 409 status))
      (is (= "S" (:title (:current body)))))))
