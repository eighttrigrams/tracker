(ns dispatch
  (:require [net.eighttrigrams.defn-over-http.core :refer [defdispatch]]
            [repository :refer [list-resources save-issue save-context]]))

(defdispatch handler 
  list-resources
  save-issue
  save-context)
