(ns dispatch
  (:require [net.eighttrigrams.defn-over-http.core :refer [defdispatch]]
            [resources :refer [list-resources]]))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defdispatch handler list-resources)
