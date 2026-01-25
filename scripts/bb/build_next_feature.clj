#!/usr/bin/env bb

(ns build-next-feature
  (:require [babashka.process :refer [shell]]
            [babashka.fs :as fs]
            [runner :as r]))

(defn validate-preconditions []
  (let [{:keys [out]} (shell {:out :string} "git" "branch" "--show-current")
        current-branch (clojure.string/trim out)]
    (when (not= "main" current-branch)
      (println "Error: Must be on main branch. Currently on:" current-branch)
      (System/exit 1)))

  (let [{:keys [out]} (shell {:out :string} "git" "status" "--porcelain")]
    (when (seq (clojure.string/trim out))
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
  (when (< (count args) 2)
    (println "Usage: bb scripts/bb/build_next_feature.clj <stages-edn> <feature-name>")
    (System/exit 1))

  (let [[stages-file feature-name] args]
    (r/load-config! stages-file)
    (validate-preconditions)
    (setup-feature-branch feature-name)
    (cleanup-workspace)
    (send-notification "Build Started" (str "Building feature: " feature-name))
    (log "Start building ...")
    (r/run-pipeline feature-name)
    (log "Done!")

    (println "\nSwitching back to main...")
    (shell "git" "switch" "main")

    (let [{:keys [out]} (shell {:out :string} "git" "rev-parse" (str "feature/" feature-name "~1"))
          commit1 (clojure.string/trim out)
          {:keys [out]} (shell {:out :string} "git" "rev-parse" (str "feature/" feature-name))
          commit2 (clojure.string/trim out)]
      (shell "git" "cherry-pick" commit1 commit2)
      (shell "git" "branch" "-D" (str "feature/" feature-name)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
