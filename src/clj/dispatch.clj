(ns dispatch
  (:require [net.eighttrigrams.defn-over-http.core :refer [defdispatch]]
            [repository :refer :all]))

(defdispatch handler 
  list-resources
  update-issue-description
  update-context-description
  new-issue)
