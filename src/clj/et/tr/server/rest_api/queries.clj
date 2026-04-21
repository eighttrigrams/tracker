(ns et.tr.server.rest-api.queries
  (:require [et.tr.db.task :as db.task]
            [et.tr.server.common :as common]
            [et.tr.server.rest-api.util :refer [json-response task->api]]))

(defn- user-id [req] (:user-id (:rest-user req)))

(defn describe
  "GET /rest/describe — self-description of the REST API. Returns one entry per
  public handler in rest-api.queries and rest-api.mutations that carries a
  docstring: {:name :ns :arglists :doc}."
  [_req]
  (json-response
    (->> ['et.tr.server.rest-api.queries 'et.tr.server.rest-api.mutations]
         (mapcat (fn [ns-sym] (when-let [n (find-ns ns-sym)] (ns-publics n))))
         (keep (fn [[sym v]]
                 (when-let [doc (:doc (meta v))]
                   {:name (str sym)
                    :ns (str (ns-name (.ns ^clojure.lang.Var v)))
                    :arglists (pr-str (:arglists (meta v)))
                    :doc doc})))
         (sort-by (juxt :ns :name))
         vec)))

(defn list-tasks
  "GET /rest/tasks?sort=<recent|today|due-date|done|manual|reminder>&q=<text>
  — list tasks for the authenticated user, matching the same sort modes as the
  web UI. Defaults to :recent."
  [req]
  (let [uid (user-id req)
        sort-mode (keyword (or (get-in req [:params "sort"]) "recent"))
        q (get-in req [:params "q"])
        tasks (db.task/list-tasks (common/ensure-ds) uid sort-mode {:search-term q})]
    (json-response (mapv task->api tasks))))

(defn list-today
  "GET /rest/tasks/today — tasks that should appear on the Today board for the
  authenticated user: not done AND (has due date, or urgent/superurgent, or
  today=1, or lined_up_for set, or reminder active)."
  [req]
  (let [tasks (db.task/list-tasks (common/ensure-ds) (user-id req) :today nil)]
    (json-response (mapv task->api tasks))))

(defn get-task
  "GET /rest/tasks/:id — fetch a single task the authenticated user owns."
  [req]
  (try (let [id (Integer/parseInt (get-in req [:params :id]))]
         (if-let [task (db.task/get-task (common/ensure-ds) (user-id req) id)]
           (json-response (task->api task))
           (json-response 404 {:error "Task not found"})))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid task ID"}))))
