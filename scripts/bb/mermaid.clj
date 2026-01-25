#!/usr/bin/env bb

(ns mermaid
  (:require [runner :as r]))

(defn -main [& args]
  (when (empty? args)
    (println "Usage: bb scripts/bb/mermaid.clj <stages-edn> [output-file]")
    (System/exit 1))
  (let [[stages-file output-file] args]
    (r/load-config! stages-file)
    (let [mermaid (r/generate-mermaid)]
      (if output-file
        (spit output-file mermaid)
        (println mermaid)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
