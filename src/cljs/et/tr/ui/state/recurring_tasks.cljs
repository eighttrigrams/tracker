(ns et.tr.ui.state.recurring-tasks
  (:require [ajax.core :refer [GET POST]]
            [clojure.string :as str]
            [reagent.core :as r]
            [et.tr.ui.api :as api]
            [et.tr.ui.state.exclusions :as exclusions]
            [et.tr.ui.state.category-filters :as category-filters]))

(defonce *recurring-tasks-page-state (r/atom {:expanded-rtask nil
                                               :editing-rtask nil
                                               :confirm-delete-rtask nil
                                               :filter-search ""
                                               :fetch-request-id 0}))

(defn- ids->names [ids collection]
  (let [id-set (set ids)
        matching (filter #(contains? id-set (:id %)) collection)]
    (mapv :name matching)))

(defn fetch-recurring-tasks [app-state auth-headers opts]
  (let [request-id (:fetch-request-id (swap! *recurring-tasks-page-state update :fetch-request-id inc))
        {:keys [search-term context strict ]} opts
        category-params (category-filters/query-string app-state opts)
        excluded-params (exclusions/query-params app-state)
        url (cond-> "/api/recurring-tasks?"
              (seq search-term) (str "q=" (js/encodeURIComponent search-term) "&")
              context (str "context=" (name context) "&")
              strict (str "strict=true&")
              (seq category-params) (str category-params)
              (seq excluded-params) (str (str/join "&" excluded-params) "&"))]
    (GET url
      {:response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [rtasks]
                  (when (= request-id (:fetch-request-id @*recurring-tasks-page-state))
                    (swap! app-state assoc :recurring-tasks rtasks)))
       :error-handler (fn [_]
                        (when (= request-id (:fetch-request-id @*recurring-tasks-page-state))
                          (swap! app-state assoc :recurring-tasks [])))})))

(defn add-recurring-task [app-state auth-headers current-scope-fn title on-success fetch-fn]
  (api/post-json "/api/recurring-tasks"
    {:title title :scope (current-scope-fn)}
    (auth-headers)
    (fn [_]
      (fetch-fn)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add recurring task")))))

(defn update-recurring-task [app-state auth-headers rtask-id title description tags expected-modified-at schedule on-success on-error]
  (api/put-json (str "/api/recurring-tasks/" rtask-id)
    (cond-> {:title title :description description :tags tags}
      expected-modified-at (assoc :expected-modified-at expected-modified-at)
      schedule (merge schedule))
    (auth-headers)
    (fn [result]
      (swap! app-state update :recurring-tasks
             (fn [rtasks]
               (mapv #(if (= (:id %) rtask-id)
                        (merge % result)
                        %)
                     rtasks)))
      (when on-success (on-success)))
    (or on-error
        (fn [resp]
          (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update recurring task"))))))

(defn delete-recurring-task [app-state auth-headers rtask-id]
  (api/delete-simple (str "/api/recurring-tasks/" rtask-id)
    (auth-headers)
    (fn [_]
      (swap! app-state update :recurring-tasks
             (fn [rtasks] (filterv #(not= (:id %) rtask-id) rtasks)))
      (swap! *recurring-tasks-page-state assoc :confirm-delete-rtask nil))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete recurring task"))
      (swap! *recurring-tasks-page-state assoc :confirm-delete-rtask nil))))

(defn set-recurring-task-scope [app-state auth-headers rtask-id scope]
  (api/put-json (str "/api/recurring-tasks/" rtask-id "/scope")
    {:scope scope}
    (auth-headers)
    (fn [result]
      (swap! app-state update :recurring-tasks
             (fn [rtasks]
               (mapv #(if (= (:id %) rtask-id)
                        (assoc % :scope (:scope result))
                        %)
                     rtasks))))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update scope")))))

(defn create-task-for-recurring
  ([app-state auth-headers fetch-fn rtask-id date time]
   (create-task-for-recurring app-state auth-headers fetch-fn rtask-id date time nil))
  ([app-state auth-headers fetch-fn rtask-id date time on-success]
   (api/post-json (str "/api/recurring-tasks/" rtask-id "/create-task")
     {:date date :time time}
     (auth-headers)
     (fn [_]
       (fetch-fn)
       (when on-success (on-success)))
     (fn [resp]
       (swap! app-state assoc :error (get-in resp [:response :error] "Failed to create task"))))))

(defn categorize-recurring-task [app-state auth-headers fetch-fn rtask-id category-type category-id]
  (api/post-json (str "/api/recurring-tasks/" rtask-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to categorize recurring task")))))

(defn uncategorize-recurring-task [app-state auth-headers fetch-fn rtask-id category-type category-id]
  (api/delete-json (str "/api/recurring-tasks/" rtask-id "/categorize")
    {:category-type category-type :category-id category-id}
    (auth-headers)
    (fn [_] (fetch-fn))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to uncategorize recurring task")))))

(defn add-recurring-task-with-categories [app-state auth-headers fetch-fn current-scope-fn title categories on-success]
  (POST "/api/recurring-tasks"
    {:params {:title title :scope (current-scope-fn)}
     :format :json
     :response-format :json
     :keywords? true
     :headers (auth-headers)
     :handler (fn [rtask]
                (category-filters/apply-filter-categories! auth-headers "recurring-tasks" (:id rtask) categories)
                (js/setTimeout fetch-fn 500)
                (swap! app-state update :recurring-tasks #(cons rtask %))
                (when on-success (on-success)))
     :error-handler (fn [resp]
                      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add recurring task")))}))

(defn set-expanded-rtask [id]
  (swap! *recurring-tasks-page-state assoc :expanded-rtask id :editing-rtask nil)
  (when (nil? id)
    (js/setTimeout #(when-let [el (.getElementById js/document "tasks-filter-search")]
                      (.focus el #js {:preventScroll true})) 0)))

(defn set-editing-rtask [id]
  (swap! *recurring-tasks-page-state assoc :editing-rtask id))

(defn clear-editing-rtask []
  (swap! *recurring-tasks-page-state assoc :editing-rtask nil))

(defn set-confirm-delete-rtask [rtask]
  (swap! *recurring-tasks-page-state assoc :confirm-delete-rtask rtask))

(defn clear-confirm-delete-rtask []
  (swap! *recurring-tasks-page-state assoc :confirm-delete-rtask nil))

(defn set-filter-search [fetch-fn search-term]
  (swap! *recurring-tasks-page-state assoc :filter-search search-term)
  (fetch-fn))

(defn clear-all-recurring-task-filters [fetch-fn]
  (swap! *recurring-tasks-page-state assoc :filter-search "")
  (fetch-fn))

(defn reset-recurring-tasks-page-view-state! []
  (swap! *recurring-tasks-page-state assoc
         :expanded-rtask nil
         :editing-rtask nil))
