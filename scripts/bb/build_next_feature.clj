#!/usr/bin/env bb

(ns build-next-feature
  (:require [babashka.process :refer [shell]]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(defn validate-preconditions []
  (let [{:keys [out]} (shell {:out :string} "git" "branch" "--show-current")
        current-branch (str/trim out)]
    (when (not= "main" current-branch)
      (println "Error: Must be on main branch. Currently on:" current-branch)
      (System/exit 1)))

  (let [{:keys [out]} (shell {:out :string} "git" "status" "--porcelain")]
    (when (seq (str/trim out))
      (println "Error: Untracked or modified files present. Please clean up first.")
      (System/exit 1))))

(defn branch-exists? [branch-name]
  (let [{:keys [exit]} (shell {:continue true :out :string :err :string}
                              "git" "show-ref" "--verify" "--quiet"
                              (str "refs/heads/" branch-name))]
    (= 0 exit)))

(defn setup-feature-branch [feature-name]
  (let [branch-name (str "feature/" feature-name)]
    (when (branch-exists? branch-name)
      (println "Error: Branch" branch-name "already exists.")
      (System/exit 1))
    (shell "git" "checkout" "-b" branch-name)))

(defn cleanup-workspace []
  (shell "make" "stop")
  (when (fs/exists? ".playwright-mcp")
    (fs/delete-tree ".playwright-mcp"))
  (when (fs/exists? "hooks.log")
    (fs/delete "hooks.log")))

(defn log [msg]
  (println msg)
  (spit "hooks.log" (str "###### " msg "\n") :append true))

(defn send-notification [title message]
  (let [send-msg "scripts/send-message.sh"]
    (when (fs/exists? send-msg)
      (shell {:continue true} send-msg title message "Tracker Builder"))))

(defn -main [& args]
  (when (< (count args) 1)
    (println "Usage: bb scripts/bb/build_next_feature.clj <feature-name>")
    (System/exit 1))

  (let [[feature-name] args
        branch-name (str "feature/" feature-name)]
    (validate-preconditions)
    (setup-feature-branch feature-name)
    (cleanup-workspace)
    (send-notification "Build Started" (str "Building feature: " feature-name))
    (log "Start building ...")

    (shell "builder" feature-name)

    (log "Done!")

    (println "\nSwitching back to main...")
    (shell "git" "switch" "main")
    (shell "git" "merge" branch-name)
    (shell "git" "branch" "-D" branch-name)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
