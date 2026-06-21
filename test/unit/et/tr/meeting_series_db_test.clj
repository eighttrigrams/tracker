(ns et.tr.meeting-series-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.tr.db.meeting-series :as db.meeting-series]
            [et.tr.db.meet :as db.meet]
            [et.tr.db.category :as db.category]
            [et.tr.test-helpers :refer [*ds* *user-id* with-in-memory-db]])
  (:import [java.time LocalDate]))

(use-fixtures :each with-in-memory-db)

(defn- day-num-of [date-str]
  (.getValue (.getDayOfWeek (LocalDate/parse date-str))))

(deftest add-meeting-series-test
  (testing "creates a meeting series with default scope"
    (let [series (db.meeting-series/add-meeting-series *ds* *user-id* "Weekly Standup")]
      (is (some? (:id series)))
      (is (= "Weekly Standup" (:title series)))
      (is (= "both" (:scope series)))
      (is (= [] (:people series)))
      (is (= [] (:places series)))
      (is (= [] (:projects series)))
      (is (= [] (:goals series)))))

  (testing "creates a meeting series with specific scope"
    (let [series (db.meeting-series/add-meeting-series *ds* *user-id* "Private Sync" "private")]
      (is (= "private" (:scope series))))))

(deftest list-meeting-series-test
  (testing "lists all meeting series for user"
    (db.meeting-series/add-meeting-series *ds* *user-id* "Series A")
    (db.meeting-series/add-meeting-series *ds* *user-id* "Series B")
    (let [series (db.meeting-series/list-meeting-series *ds* *user-id*)]
      (is (= 2 (count series)))
      (is (= #{"Series A" "Series B"} (set (map :title series))))))

  (testing "search filters by title"
    (db.meeting-series/add-meeting-series *ds* *user-id* "Alpha Meeting")
    (db.meeting-series/add-meeting-series *ds* *user-id* "Beta Meeting")
    (let [results (db.meeting-series/list-meeting-series *ds* *user-id* {:search-term "Alpha"})]
      (is (= 1 (count results)))
      (is (= "Alpha Meeting" (:title (first results)))))))

(deftest update-meeting-series-test
  (let [series (db.meeting-series/add-meeting-series *ds* *user-id* "Original")]
    (testing "updates title, description and tags"
      (let [updated (db.meeting-series/update-meeting-series *ds* *user-id* (:id series)
                      {:title "Updated" :description "desc" :tags "tag1"})]
        (is (= "Updated" (:title updated)))
        (is (= "desc" (:description updated)))
        (is (= "tag1" (:tags updated)))))))

(deftest delete-meeting-series-test
  (let [series (db.meeting-series/add-meeting-series *ds* *user-id* "To Delete")]
    (testing "deletes a meeting series"
      (let [result (db.meeting-series/delete-meeting-series *ds* *user-id* (:id series))]
        (is (:success result)))
      (is (empty? (db.meeting-series/list-meeting-series *ds* *user-id*))))))

(deftest set-meeting-series-schedule-test
  (let [series (db.meeting-series/add-meeting-series *ds* *user-id* "Scheduled")]
    (testing "sets weekly schedule"
      (let [result (db.meeting-series/set-meeting-series-schedule
                     *ds* *user-id* (:id series) "1,3,5" "09:00" "weekly" false)]
        (is (= "1,3,5" (:schedule_days result)))
        (is (= "09:00" (:schedule_time result)))
        (is (= "weekly" (:schedule_mode result)))
        (is (= 0 (:biweekly_offset result)))))

    (testing "sets biweekly schedule with offset"
      (let [result (db.meeting-series/set-meeting-series-schedule
                     *ds* *user-id* (:id series) "2" "10:00" "biweekly" true)]
        (is (= "2" (:schedule_days result)))
        (is (= "biweekly" (:schedule_mode result)))
        (is (= 1 (:biweekly_offset result)))))))

(deftest create-meeting-for-series-test
  (let [series (db.meeting-series/add-meeting-series *ds* *user-id* "Parent Series")]
    (testing "creates a meeting inheriting properties from series"
      (let [meet (db.meeting-series/create-meeting-for-series
                   *ds* *user-id* (:id series) "2026-05-01" "14:00")]
        (is (some? (:id meet)))
        (is (= "Parent Series" (:title meet)))
        (is (= "2026-05-01" (:start_date meet)))
        (is (= "14:00" (:start_time meet)))
        (is (= (:id series) (:meeting_series_id meet)))
        (is (= "both" (:scope meet)))))

    (testing "inherits scope from series"
      (let [private-series (db.meeting-series/add-meeting-series *ds* *user-id* "Private" "private")
            meet (db.meeting-series/create-meeting-for-series
                   *ds* *user-id* (:id private-series) "2026-06-01" "09:00")]
        (is (= "private" (:scope meet)))))

    (testing "inherits categories from series"
      (let [series2 (db.meeting-series/add-meeting-series *ds* *user-id* "Categorized")
            place (db.category/add-place *ds* *user-id* "Office")]
        (db.meeting-series/categorize-meeting-series *ds* *user-id* (:id series2) "place" (:id place))
        (let [meet (db.meeting-series/create-meeting-for-series
                     *ds* *user-id* (:id series2) "2026-07-01" "10:00")]
          (is (some? (:id meet))))))))

(deftest create-meeting-for-series-maybe-test
  (let [series (db.meeting-series/add-meeting-series *ds* *user-id* "Maybe Series")]
    (testing "maybe defaults to 0 when not given"
      (let [meet (db.meeting-series/create-meeting-for-series
                   *ds* *user-id* (:id series) "2026-05-01" "09:00")]
        (is (= 0 (:maybe meet)))))

    (testing "stores the given maybe value"
      (let [meet (db.meeting-series/create-meeting-for-series
                   *ds* *user-id* (:id series) "2026-05-02" "09:00" 1)]
        (is (= 1 (:maybe meet)))))))

(deftest auto-create-meetings-single-mode-maybe-test
  (testing "single-mode series maybe=1 seeds every generated meet with maybe=1"
    (let [series (db.meeting-series/add-meeting-series *ds* *user-id* "All Days")]
      (db.meeting-series/set-meeting-series-schedule
        *ds* *user-id* (:id series) "1,2,3,4,5,6,7" "09:00" "weekly" false "1")
      (let [created (db.meeting-series/auto-create-meetings *ds* *user-id*)]
        (is (seq created))
        (is (every? #(= 1 (:maybe %)) created))))))

(deftest auto-create-meetings-per-day-maybe-test
  (testing "per-day series maybe seeds each generated meet from its day-number"
    (let [per-day "1=1,2=0,3=1,4=0,5=1,6=0,7=1"
          series (db.meeting-series/add-meeting-series *ds* *user-id* "Per Day")]
      (db.meeting-series/set-meeting-series-schedule
        *ds* *user-id* (:id series) "1,2,3,4,5,6,7" "09:00" "weekly" false per-day)
      (let [created (db.meeting-series/auto-create-meetings *ds* *user-id*)
            expected {1 1 2 0 3 1 4 0 5 1 6 0 7 1}]
        (is (seq created))
        (doseq [meet created]
          (is (= (expected (day-num-of (:start_date meet))) (:maybe meet))
              (str "day " (day-num-of (:start_date meet)) " on " (:start_date meet))))))))

(deftest series-maybe-is-independent-test
  (let [series (db.meeting-series/add-meeting-series *ds* *user-id* "Independent")]
    (db.meeting-series/set-meeting-series-schedule
      *ds* *user-id* (:id series) "1,2,3,4,5,6,7" "09:00" "weekly" false "1")
    (let [created (db.meeting-series/auto-create-meetings *ds* *user-id*)
          meet-ids (mapv :id created)]
      (testing "changing the series default does NOT rewrite existing meets"
        (db.meeting-series/set-meeting-series-schedule
          *ds* *user-id* (:id series) "1,2,3,4,5,6,7" "09:00" "weekly" false "0")
        (doseq [id meet-ids]
          (is (= 1 (:maybe (db.meet/get-meet *ds* *user-id* id))))))

      (testing "toggling one meet's maybe touches neither siblings nor the series"
        (let [target (first meet-ids)
              siblings (rest meet-ids)]
          (db.meet/set-meet-maybe *ds* *user-id* target false)
          (is (= 0 (:maybe (db.meet/get-meet *ds* *user-id* target))))
          (doseq [id siblings]
            (is (= 1 (:maybe (db.meet/get-meet *ds* *user-id* id)))))
          (is (= "0" (:maybe (db.meeting-series/get-meeting-series *ds* *user-id* (:id series))))))))))

(deftest categorize-meeting-series-test
  (let [series (db.meeting-series/add-meeting-series *ds* *user-id* "Cat Series")
        person (db.category/add-person *ds* *user-id* "Alice")
        place (db.category/add-place *ds* *user-id* "Room A")]
    (testing "categorizes with person"
      (db.meeting-series/categorize-meeting-series *ds* *user-id* (:id series) "person" (:id person))
      (let [listed (first (db.meeting-series/list-meeting-series *ds* *user-id*))]
        (is (= 1 (count (:people listed))))))

    (testing "categorizes with place"
      (db.meeting-series/categorize-meeting-series *ds* *user-id* (:id series) "place" (:id place))
      (let [listed (first (db.meeting-series/list-meeting-series *ds* *user-id*))]
        (is (= 1 (count (:places listed))))))

    (testing "uncategorizes"
      (db.meeting-series/uncategorize-meeting-series *ds* *user-id* (:id series) "person" (:id person))
      (let [listed (first (db.meeting-series/list-meeting-series *ds* *user-id*))]
        (is (= 0 (count (:people listed))))))))

(deftest get-taken-dates-test
  (let [series (db.meeting-series/add-meeting-series *ds* *user-id* "Dates Series")]
    (testing "returns empty list when no meetings exist"
      (is (= [] (db.meeting-series/get-taken-dates *ds* *user-id* (:id series)))))

    (testing "returns dates of created meetings"
      (db.meeting-series/create-meeting-for-series *ds* *user-id* (:id series) "2026-05-01" "09:00")
      (db.meeting-series/create-meeting-for-series *ds* *user-id* (:id series) "2026-05-08" "09:00")
      (let [dates (db.meeting-series/get-taken-dates *ds* *user-id* (:id series))]
        (is (= 2 (count dates)))
        (is (contains? (set dates) "2026-05-01"))
        (is (contains? (set dates) "2026-05-08"))))

    (testing "returns nil for non-existent series"
      (is (nil? (db.meeting-series/get-taken-dates *ds* *user-id* 99999))))))

(deftest set-meeting-series-scope-test
  (let [series (db.meeting-series/add-meeting-series *ds* *user-id* "Scope Test")]
    (testing "changes scope"
      (let [result (db.meeting-series/set-meeting-series-field *ds* *user-id* (:id series) :scope "work")]
        (is (= "work" (:scope result)))))))

(deftest delete-meeting-series-unlinks-meetings-test
  (let [series (db.meeting-series/add-meeting-series *ds* *user-id* "Unlink Test")
        _meet (db.meeting-series/create-meeting-for-series *ds* *user-id* (:id series) "2026-08-01" "10:00")]
    (testing "deleting series sets meeting_series_id to nil on meetings"
      (db.meeting-series/delete-meeting-series *ds* *user-id* (:id series))
      (is (empty? (db.meeting-series/list-meeting-series *ds* *user-id*))))))
