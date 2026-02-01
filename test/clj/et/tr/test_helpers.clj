(ns et.tr.test-helpers
  (:require [et.tr.db :as db]
            [clojure.java.io :as io]
            [taoensso.telemere :as tel]))

(tel/remove-handler! :default/console)
(let [log-dir (io/file "logs")
      log-file (io/file "logs/tracker.tests.log")]
  (.mkdirs log-dir)
  (when (.exists log-file) (.delete log-file))
  (tel/add-handler! :test-file (tel/handler:file {:path "logs/tracker.tests.log"})))

(def ^:dynamic *ds* nil)

(defn with-in-memory-db [f]
  (let [conn (db/init-conn {:type :sqlite-memory})]
    (try
      (binding [*ds* conn]
        (f))
      (finally
        (when-let [pc (:persistent-conn conn)]
          (.close pc))))))
