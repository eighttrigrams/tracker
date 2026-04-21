(ns et.tr.server.rest-api.util
  (:require [clojure.data.json :as json]))

(defn json-response
  ([body] (json-response 200 body))
  ([status body]
   {:status status
    :headers {"Content-Type" "application/json"}
    :body (json/write-str body)}))

(defn parse-json-body
  [req]
  (let [body (:body req)]
    (cond
      (map? body) body
      (nil? body) nil
      :else (try (json/read-str (slurp body) :key-fn keyword)
                 (catch Exception _ nil)))))

(defn task->api
  [task]
  (select-keys task
               [:id :title :description :tags :done :today :urgency :importance :scope
                :due_date :due_time :lined_up_for :reminder :reminder_date
                :created_at :modified_at :done_at :sort_order :recurring_task_id
                :people :places :projects :goals]))
