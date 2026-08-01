(ns et.tr.day-order-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [et.tr.integration-helpers :refer [*ds* *user-id* GET-json POST-json PUT-json
                                               with-integration-db]]
            [et.tr.clock :as clock]
            [et.tr.db.task :as db.task]
            [et.tr.db.user :as db.user]))

(use-fixtures :each with-integration-db)

(defn- today [] (clock/today-str))

(defn- days-from-today [n]
  (str (.plusDays (java.time.LocalDate/parse (today)) n)))

(defn- add-task! [title]
  (:body (POST-json "/api/tasks" {:title title})))

(defn- task [id]
  (:body (GET-json (str "/api/tasks/" id))))

(defn- add-meet! [title date time]
  (let [meet (:body (POST-json "/api/meets" {:title title}))]
    (PUT-json (str "/api/meets/" (:id meet) "/start-date") {:start-date date})
    (PUT-json (str "/api/meets/" (:id meet) "/start-time") {:start-time time})
    meet))

(defn- flag-for-today! [title]
  (let [id (:id (add-task! title))]
    (PUT-json (str "/api/tasks/" id "/today") {:today true})
    id))

(defn- drop-on [task-id date target-type target-id position]
  (POST-json (str "/api/tasks/" task-id "/reorder-today")
             {:date date :target-type target-type :target-id target-id :position position}))

(defn- day-list
  ([] (day-list 0))
  ([offset]
   (let [board (:body (GET-json (str "/api/today-board?days=" (max offset 1))))
         by-date (into {} (map (juxt :date :items)) (:days board))
         titles (into {} (map (juxt :id :title)) (:tasks board))
         meet-titles (into {} (map (juxt :id :title)) (:meets board))]
     (mapv (fn [{:keys [type id]}]
             (if (= "meet" type) (meet-titles id) (titles id)))
           (get by-date (days-from-today offset))))))

(deftest a-task-on-no-day-list-carries-no-position
  (testing "nothing is materialized until the task joins a day"
    (let [id (:id (add-task! "Nothing scheduled"))]
      (is (nil? (:sort_order_today (task id)))))))

(deftest joining-a-day-materializes-a-position-at-its-end
  (testing "each new member lands past everything the day already holds"
    (let [first-id (flag-for-today! "First in")
          second-id (flag-for-today! "Second in")]
      (is (some? (:sort_order_today (task first-id))))
      (is (< (:sort_order_today (task first-id)) (:sort_order_today (task second-id))))
      (is (= ["First in" "Second in"] (day-list)))))

  (testing "a day with meets in it puts the new task after the last one"
    (add-meet! "Standup" (today) "08:30")
    (let [id (flag-for-today! "After the meets")]
      (is (< 510.0 (:sort_order_today (task id))))
      (is (= ["Standup" "First in" "Second in" "After the meets"] (day-list)))))

  (testing "one step past the last item, not past the whole day: 08:30 is 510"
    (let [date (days-from-today 2)
          _ (add-meet! "Kickoff" date "08:30")
          id (:id (add-task! "Right after the kickoff"))]
      (PUT-json (str "/api/tasks/" id "/lined-up-for") {:lined_up_for date})
      (is (= 511.0 (:sort_order_today (task id))))))

  (testing "and a day holding nothing else starts the band past the last minute"
    (let [date (days-from-today 3)
          id (:id (add-task! "First on an empty day"))]
      (PUT-json (str "/api/tasks/" id "/lined-up-for") {:lined_up_for date})
      (is (= 1441.0 (:sort_order_today (task id)))))))

(deftest a-drop-moves-the-task-to-where-it-was-dropped
  (testing "the caller names a target and a side; the server computes the value"
    (let [_ (add-meet! "Standup" (today) "08:30")
          late (:id (add-meet! "Retro" (today) "15:00"))
          id (flag-for-today! "Tidy the desk")
          {:keys [status body]} (drop-on id (today) "meet" late "before")]
      (is (= 200 status))
      (is (true? (:success body)))
      (is (= (:sort_order_today body) (:sort_order_today (task id))))
      (is (= ["Standup" "Tidy the desk" "Retro"] (day-list))))))

(deftest a-drop-brings-a-task-into-the-day-and-places-it-in-one-request
  (testing "a task no day list holds joins the day it was dropped on"
    (let [standup (:id (add-meet! "Standup" (today) "08:30"))
          _ (add-meet! "Retro" (today) "15:00")
          id (:id (add-task! "Fix prod bug"))]
      (is (= 200 (:status (drop-on id (today) "meet" standup "after"))))
      (is (= 1 (:today (task id))))
      (is (= ["Standup" "Fix prod bug" "Retro"] (day-list)))))

  (testing "a later day is joined through its lined-up date"
    (let [tomorrow (days-from-today 1)
          _ (add-meet! "Tomorrow's kickoff" tomorrow "10:00")
          anchor (flag-for-today! "placeholder")
          lined-up (:id (add-task! "Lined up"))]
      (PUT-json (str "/api/tasks/" lined-up "/lined-up-for") {:lined_up_for tomorrow})
      (is (= 200 (:status (drop-on lined-up tomorrow "meet"
                                   (:id (add-meet! "Tomorrow's retro" tomorrow "16:00"))
                                   "before"))))
      (is (= tomorrow (:lined_up_for (task lined-up))))
      (is (= ["Tomorrow's kickoff" "Lined up" "Tomorrow's retro"] (day-list 1)))
      (is (some? anchor)))))

(deftest the-day-list-order-and-the-tasks-page-order-are-independent
  (testing "moving a task in the day list leaves the Tasks page order alone"
    (let [first-id (flag-for-today! "First")
          second-id (flag-for-today! "Second")
          before (:sort_order (task second-id))]
      (is (= 200 (:status (drop-on second-id (today) "task" first-id "before"))))
      (is (= before (:sort_order (task second-id))))
      (is (= ["Second" "First"] (day-list)))))

  (testing "moving a task on the Tasks page leaves the day list order alone"
    (let [first-id (flag-for-today! "Third")
          second-id (flag-for-today! "Fourth")
          before (:sort_order_today (task second-id))]
      (is (= 200 (:status (POST-json (str "/api/tasks/" second-id "/reorder")
                                     {:target-task-id first-id :position "before"}))))
      (is (= before (:sort_order_today (task second-id))))
      (is (= ["Second" "First" "Third" "Fourth"] (day-list))))))

(deftest a-due-date-change-takes-the-position-only-when-it-takes-the-day
  (testing "the stored position is relative to one day, so dating the task clears it"
    (let [id (flag-for-today! "Moves to another day")]
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/due-date") {:due-date (days-from-today 1)}))))
      (is (nil? (:sort_order_today (task id))))))

  (testing "clearing the date of a task no other marker holds clears it too"
    (let [anchor (flag-for-today! "Anchor")
          id (:id (add-task! "Only ever had a date"))]
      (PUT-json (str "/api/tasks/" id "/due-date") {:due-date (today)})
      (drop-on id (today) "task" anchor "after")
      (is (some? (:sort_order_today (task id))))
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/due-date") {:due-date nil}))))
      (is (nil? (:sort_order_today (task id))))))

  (testing "but a task marked for a day never left it, and keeps its place there"
    (let [first-id (flag-for-today! "Already here")
          id (:id (add-task! "Stays on today"))]
      (PUT-json (str "/api/tasks/" id "/due-date") {:due-date (days-from-today 1)})
      (PUT-json (str "/api/tasks/" id "/today") {:today true})
      (drop-on id (today) "task" first-id "before")
      (let [placed (:sort_order_today (task id))]
        (is (some? placed))
        (is (= ["Anchor" "Stays on today" "Already here"] (day-list)))
        (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/due-date") {:due-date nil}))))
        (is (= 1 (:today (task id))))
        (is (= placed (:sort_order_today (task id))))
        (is (= ["Anchor" "Stays on today" "Already here"] (day-list))))))

  (testing "and the same for a task queued for a later day"
    (let [tomorrow (days-from-today 1)
          id (:id (add-task! "Stays on tomorrow"))]
      (PUT-json (str "/api/tasks/" id "/due-date") {:due-date (days-from-today 3)})
      (PUT-json (str "/api/tasks/" id "/lined-up-for") {:lined_up_for tomorrow})
      (let [placed (:sort_order_today (task id))]
        (is (some? placed))
        (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/due-date") {:due-date nil}))))
        (is (= tomorrow (:lined_up_for (task id))))
        (is (= placed (:sort_order_today (task id))))))))

(deftest leaving-the-day-lists-drops-the-position
  (testing "unlinking from today clears it"
    (let [id (flag-for-today! "Unlinked")]
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/today") {:today false}))))
      (is (nil? (:sort_order_today (task id))))))

  (testing "clearing the queued day clears it too"
    (let [id (:id (add-task! "Unqueued"))]
      (PUT-json (str "/api/tasks/" id "/lined-up-for") {:lined_up_for (days-from-today 2)})
      (is (some? (:sort_order_today (task id))))
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/lined-up-for") {:lined_up_for nil}))))
      (is (nil? (:sort_order_today (task id))))))

  (testing "being done takes the task off every day list, position included"
    (let [id (flag-for-today! "Finished")]
      (is (= 200 (:status (PUT-json (str "/api/tasks/" id "/done") {:done true}))))
      (is (nil? (:sort_order_today (task id)))))))

(deftest changing-days-re-places-the-task-at-the-end-of-the-new-one
  (testing "a position belongs to the day it was arranged on, not to the task"
    (let [tomorrow (days-from-today 1)
          held (:id (add-task! "Already there"))
          moved (:id (add-task! "Sent to tomorrow"))]
      (PUT-json (str "/api/tasks/" held "/lined-up-for") {:lined_up_for tomorrow})
      (PUT-json (str "/api/tasks/" moved "/today") {:today true})
      (let [on-today (:sort_order_today (task moved))]
        (PUT-json (str "/api/tasks/" moved "/lined-up-for") {:lined_up_for tomorrow})
        (is (not= on-today (:sort_order_today (task moved))))
        (is (< (:sort_order_today (task held)) (:sort_order_today (task moved))))
        (is (= ["Already there" "Sent to tomorrow"] (day-list 1)))))))

(deftest the-worker-promotion-keeps-the-position
  (testing "lined up for today and marked for today are the same day, so nothing moves"
    (let [id (:id (add-task! "Promoted"))]
      (PUT-json (str "/api/tasks/" id "/lined-up-for") {:lined_up_for (today)})
      (let [before (:sort_order_today (task id))]
        (db.task/promote-lined-up-tasks! *ds* *user-id*)
        (is (= 1 (:today (task id))))
        (is (= before (:sort_order_today (task id)))))))

  (testing "a row that never got one is placed at the end"
    (let [id (:id (add-task! "Older row"))]
      (PUT-json (str "/api/tasks/" id "/lined-up-for") {:lined_up_for (today)})
      (db.task/set-task-sort-order-today *ds* *user-id* id nil)
      (db.task/promote-lined-up-tasks! *ds* *user-id*)
      (is (some? (:sort_order_today (task id)))))))

(deftest a-malformed-drop-is-refused
  (let [target (flag-for-today! "Target")
        id (flag-for-today! "Dragged")
        placed (:sort_order_today (task id))]
    (doseq [[body error]
            [[{:date "nope" :target-type "task" :target-id target :position "after"}
              "date must be a YYYY-MM-DD string"]
             [{:date (today) :target-type "note" :target-id target :position "after"}
              "target-type must be \"task\" or \"meet\""]
             [{:date (today) :target-type "task" :target-id "12" :position "after"}
              "target-id must be an integer"]
             [{:date (today) :target-type "task" :target-id target :position "sideways"}
              "position must be \"before\" or \"after\""]
             [{:date (days-from-today 3) :target-type "task" :target-id target :position "after"}
              "Target is not in that day's list"]]]
      (let [resp (POST-json (str "/api/tasks/" id "/reorder-today") body)]
        (is (= 400 (:status resp)) (pr-str body))
        (is (= {:error error} (:body resp)) (pr-str body))))
    (is (= placed (:sort_order_today (task id))))))

(deftest unknown-and-foreign-tasks-404
  (testing "an id that is not the current user's task is refused"
    (let [target (flag-for-today! "Mine")
          other (db.user/create-user *ds* "other-user" "testpass")
          theirs (:id (db.task/add-task *ds* (:id other) "Not yours"))]
      (is (= 404 (:status (drop-on 99999 (today) "task" target "after"))))
      (let [{:keys [status body]} (drop-on theirs (today) "task" target "after")]
        (is (= 404 status))
        (is (= {:error "Task not found"} body)))
      (is (nil? (:sort_order_today (db.task/get-task *ds* (:id other) theirs)))))))

(deftest the-today-board-day-window-covers-the-day-buttons
  (testing "one request carries every day the day selector offers"
    (let [board (:body (GET-json "/api/today-board?days=4"))]
      (is (= (mapv days-from-today (range 5)) (mapv :date (:days board))))))

  (testing "days defaults to today alone"
    (is (= [(today)] (mapv :date (:days (:body (GET-json "/api/today-board")))))))

  (testing "each item points at a row the same response carries"
    (add-meet! "Standup" (today) "08:30")
    (flag-for-today! "Tidy the desk")
    (let [board (:body (GET-json "/api/today-board"))
          items (:items (first (:days board)))]
      (is (= [{:type "meet" :flagged false} {:type "task" :flagged true}]
             (mapv #(dissoc % :id) items)))
      (is (contains? (set (map :id (:meets board))) (:id (first items))))
      (is (contains? (set (map :id (:tasks board))) (:id (second items)))))))
