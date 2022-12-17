(ns server
  (:require [ring.adapter.jetty :as j]
            [compojure.core :refer [defroutes GET POST]]
            [ring.util.response :as response]
            [ring.middleware.json :as json]
            [env :refer [wrap-env-defaults]]
            [mount.core :as mount]
            [resources :as r]
            [ring.middleware.resource :refer [wrap-resource]]))

(defn api-handler [{{msg :msg} :body}]
  (tap> [:resources (r/get-resources)])
  {:body {:echo msg}})

(defn wrap-api [handler]
  (-> handler
      json/wrap-json-response
      (json/wrap-json-body {:keywords? true})))

(defroutes routes
  (POST "/api" [] (wrap-api api-handler))
  (GET "/" [] (response/resource-response "public/index.html")))

(def app
  (-> routes
      wrap-env-defaults
      (wrap-resource "public")))

(mount/defstate ^{:on-reload :noop} http-server
  :start
  (future (j/run-jetty app {:port 3000}))
  :stop 0)

(defn -main
  [& _args]
  (prn (mount/start))
  (.addShutdownHook (Runtime/getRuntime) (Thread. #(prn (mount/stop))))
  (deref http-server))