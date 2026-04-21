(ns et.tr.server.rest-api
  (:require [compojure.core :refer [context GET POST PUT]]
            [et.tr.server.rest-api.middleware :as mw]
            [et.tr.server.rest-api.queries :as queries]
            [et.tr.server.rest-api.mutations :as mutations]))

(defn rest-routes []
  (mw/wrap-rest-auth
    (context "/rest" []
      (POST "/auth/login" [] mutations/login)
      (GET "/describe" [] queries/describe)
      (POST "/recording-mode/toggle" [] mutations/toggle-recording-mode)
      (GET "/tasks" [] queries/list-tasks)
      (GET "/tasks/today" [] queries/list-today)
      (GET "/tasks/:id" [] queries/get-task)
      (POST "/tasks" [] mutations/create-task)
      (PUT "/tasks/:id/done" [] mutations/set-task-done)
      (PUT "/tasks/:id/today" [] mutations/set-task-today))))
