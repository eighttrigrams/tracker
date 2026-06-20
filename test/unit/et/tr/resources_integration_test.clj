(ns et.tr.resources-integration-test
  (:require [clojure.test :refer :all]
            [et.tr.integration-helpers :refer :all]))

(use-fixtures :each with-integration-db)

(defn- add-sheet! [title]
  (:body (POST-json "/api/resources" {:title title})))

(defn- update-resource! [id fields]
  (PUT-json (str "/api/resources/" id) fields))

(defn- get-resource [id]
  (:body (GET-json (str "/api/resources/" id))))

(defn- seed! [n]
  (dotimes [i n] (add-sheet! (str "R" i))))

(deftest update-sheet-description
  (let [sheet (add-sheet! "My Sheet")
        resp (update-resource! (:id sheet) {:title "My Sheet" :description "Some notes"})]
    (is (= 200 (:status resp)))
    (let [fetched (get-resource (:id sheet))]
      (is (= "Some notes" (:description fetched))))))

(deftest update-resource-with-link-description
  (let [resource (:body (POST-json "/api/resources" {:title "A Link" :link "https://example.com"}))
        resp (update-resource! (:id resource) {:title "A Link" :link "https://example.com" :description "Link notes"})]
    (is (= 200 (:status resp)))
    (let [fetched (get-resource (:id resource))]
      (is (= "Link notes" (:description fetched))))))

(deftest title-only-update-preserves-description
  (testing "a title-only update (no :description key) leaves the stored description untouched"
    (let [sheet (add-sheet! "My Sheet")]
      (update-resource! (:id sheet) {:title "My Sheet" :description "Some notes"})
      (let [resp (update-resource! (:id sheet) {:title "Renamed Sheet"})]
        (is (= 200 (:status resp))))
      (let [fetched (get-resource (:id sheet))]
        (is (= "Renamed Sheet" (:title fetched)))
        (is (= "Some notes" (:description fetched)))))))

(deftest explicit-blank-description-clears
  (testing "an explicit :description \"\" still empties the stored description"
    (let [sheet (add-sheet! "My Sheet")]
      (update-resource! (:id sheet) {:title "My Sheet" :description "Some notes"})
      (let [resp (update-resource! (:id sheet) {:title "My Sheet" :description ""})]
        (is (= 200 (:status resp))))
      (let [fetched (get-resource (:id sheet))]
        (is (= "" (:description fetched)))))))

(deftest title-only-update-preserves-tags
  (testing "a title-only update leaves stored tags untouched"
    (let [sheet (add-sheet! "Tagged")]
      (update-resource! (:id sheet) {:title "Tagged" :tags "alpha beta"})
      (update-resource! (:id sheet) {:title "Tagged Renamed"})
      (let [fetched (get-resource (:id sheet))]
        (is (= "alpha beta" (:tags fetched)))))))

(deftest paged-envelope-shape
  (testing "paged=true wraps the response as {:items :has_more}"
    (seed! 3)
    (let [{:keys [status body]} (GET-json "/api/resources?paged=true&limit=10&offset=0")]
      (is (= 200 status))
      (is (map? body))
      (is (vector? (:items body)))
      (is (= 3 (count (:items body))))
      (is (contains? body :has_more))
      (is (false? (:has_more body)))))
  (testing "without paged the response is a bare vector"
    (let [{:keys [body]} (GET-json "/api/resources")]
      (is (vector? body)))))

(deftest paged-without-limit-returns-full-set
  (testing "paged=true without a limit returns the whole set with has_more false"
    (seed! 3)
    (let [{:keys [status body]} (GET-json "/api/resources?paged=true")]
      (is (= 200 status))
      (is (map? body))
      (is (= 3 (count (:items body))))
      (is (false? (:has_more body))))))

(deftest has-more-boundary
  (testing "exactly page-size rows -> has_more false, all rows returned"
    (seed! 3)
    (let [{:keys [body]} (GET-json "/api/resources?paged=true&limit=3&offset=0")]
      (is (= 3 (count (:items body))))
      (is (false? (:has_more body)))))
  (testing "page-size + 1 rows -> has_more true, trimmed to page-size"
    (add-sheet! "extra")
    (let [{:keys [body]} (GET-json "/api/resources?paged=true&limit=3&offset=0")]
      (is (= 3 (count (:items body))))
      (is (true? (:has_more body))))))

(deftest lean-default-vs-detail-full
  (testing "lean default drops :description but keeps :tags; ?detail=full keeps :description"
    (let [sheet (add-sheet! "Notes sheet")]
      (update-resource! (:id sheet) {:title "Notes sheet" :description "the body" :tags "alpha beta"})
      (let [lean (first (:body (GET-json "/api/resources")))
            full (first (:body (GET-json "/api/resources?detail=full")))]
        (is (= "Notes sheet" (:title lean)))
        (is (not (contains? lean :description)))
        (is (contains? lean :tags))
        (is (= "alpha beta" (:tags lean)))
        (is (= "the body" (:description full)))))))

(deftest has-more-with-offset
  (testing "has_more reflects the limit+1 probe at each offset, true until the final page"
    (seed! 5)
    (let [page (fn [off] (:body (GET-json (str "/api/resources?paged=true&sortMode=manual&limit=2&offset=" off))))]
      (is (true? (:has_more (page 0))))
      (is (= 2 (count (:items (page 0)))))
      (is (true? (:has_more (page 2))))
      (is (= 2 (count (:items (page 2)))))
      (is (false? (:has_more (page 4))))
      (is (= 1 (count (:items (page 4))))))))

(deftest offset-paging-via-endpoint
  (testing "limit + offset page through the full set without overlap"
    (seed! 5)
    (let [ids (fn [path] (mapv :id (:items (:body (GET-json path)))))
          all (mapv :id (:body (GET-json "/api/resources?sortMode=manual")))
          page1 (ids "/api/resources?paged=true&sortMode=manual&limit=2&offset=0")
          page2 (ids "/api/resources?paged=true&sortMode=manual&limit=2&offset=2")
          page3 (ids "/api/resources?paged=true&sortMode=manual&limit=2&offset=4")]
      (is (= 5 (count all)))
      (is (= [2 2 1] [(count page1) (count page2) (count page3)]))
      (is (= all (concat page1 page2 page3)))
      (is (= (set all) (set (concat page1 page2 page3)))))))
