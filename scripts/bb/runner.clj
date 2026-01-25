(ns runner
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.process :refer [shell process]]
            [babashka.fs :as fs]))

(def ^:dynamic *config* nil)

(defn load-config! [path]
  (alter-var-root #'*config* (constantly (edn/read-string (slurp path)))))

(defn docs-dir [] (:docs-dir *config*))
(defn documents [] (:documents *config*))

(defn doc-path [doc-key]
  (str (docs-dir) "/" (:file (get (documents) doc-key))))

(defn doc-allow-single-line? [doc-key]
  (:allow-single-line? (get (documents) doc-key)))

(defn interpolate [template ctx]
  (reduce-kv
   (fn [s k v]
     (str/replace s (str "{{" (name k) "}}") v))
   template
   (merge ctx
          (into {} (map (fn [[k _]] [k (doc-path k)]) (documents))))))

(defn file-valid?
  ([path] (file-valid? path false))
  ([path allow-single-line?]
   (and (fs/exists? path)
        (let [lines (count (str/split-lines (slurp path)))]
          (if allow-single-line?
            (>= lines 1)
            (> lines 1))))))

(defn check-requires [{:keys [requires]} ctx]
  (when requires
    (doseq [doc-key requires]
      (let [path (doc-path doc-key)
            allow-single? (doc-allow-single-line? doc-key)]
        (when-not (file-valid? path allow-single?)
          (println "Error: Required document" path "not found or empty.")
          (System/exit 1))))))

(defn check-produces [{:keys [produces]} ctx]
  (when produces
    (doseq [doc-key produces]
      (let [path (doc-path doc-key)
            allow-single? (doc-allow-single-line? doc-key)]
        (when-not (file-valid? path allow-single?)
          (println "Error: Expected output" path "not found or empty.")
          (System/exit 1))))))

(defn build-prompt [{:keys [requires prompt]} ctx]
  (when prompt
    (let [read-section (when (seq requires)
                         (str "Read the following documents:\n"
                              (str/join "\n" (map #(str "- " (doc-path %)) requires))
                              "\n\n"))]
      (interpolate (str read-section prompt) ctx))))

(defn run-tests []
  (println "Running tests...")
  (let [{:keys [exit]} (shell {:continue true} "clj" "-X:test")]
    (when (not= 0 exit)
      (println "Unit tests failed. Aborting.")
      (System/exit 1))))

(defn git-commit [message feature-name]
  (shell "git" "add" ".")
  (shell "git" "reset" "HEAD" "--" (str docs-dir "/"))
  (shell "git" "commit" "-m" (str "feature/" feature-name " - " message)))

(defn cleanup-docs [doc-keys]
  (doseq [doc-key doc-keys]
    (let [path (doc-path doc-key)]
      (when (fs/exists? path)
        (fs/delete path)))))

(defn run-claude [prompt]
  (println "Running Claude...")
  (shell "claude" "-p" prompt "--allowedTools" "Write"))

(defn start-app []
  (println "Starting app...")
  (shell "make" "stop")
  ;; Don't inherit stdout - it blocks subsequent prints until user presses enter
  (process "make" "start")
  (Thread/sleep 2000))

(defn stop-app []
  (println "Stopping app...")
  (shell "make" "stop"))

(defn create-human-opinion-if-missing []
  (let [path (doc-path :human-opinion)]
    (when-not (fs/exists? path)
      (print (str path " not found. Create it? (y|n): "))
      (flush)
      (when (= "y" (str/trim (read-line)))
        (spit path "")
        (shell "code" path)))))

(defn wait-for-human [message]
  (shell "say" "Tracker needs your attention now.")
  (create-human-opinion-if-missing)
  (println message)
  (loop []
    (print "Type 'ok' to proceed: ")
    (flush)
    (when (not= "ok" (str/trim (read-line)))
      (recur))))

(defn run-stage [stage ctx]
  (let [{:keys [id prompt human-input? start-app? stop-app? run-tests?
                commit cleanup cleanup-after git-revert? amend-commit?
                clear-next-feature? message]} stage]
    (println "\n=== Stage:" (name id) "===")

    (check-requires stage ctx)

    (when cleanup
      (cleanup-docs cleanup))

    (when git-revert?
      (shell "git" "revert" "--no-edit" "HEAD"))

    (when start-app?
      (start-app))

    (when stop-app?
      (stop-app))

    (when human-input?
      (wait-for-human (interpolate (or message "Waiting for human input...") ctx)))

    (when prompt
      (run-claude (build-prompt stage ctx)))

    (check-produces stage ctx)

    (when run-tests?
      (run-tests))

    (when commit
      (git-commit (:message commit) (:feature-name ctx)))

    (when amend-commit?
      (let [body (when (fs/exists? (doc-path :commit-message-body))
                   (slurp (doc-path :commit-message-body)))]
        (shell "git" "commit" "--amend" "-m"
               (str "feature/" (:feature-name ctx) " - Implementation")
               "-m" (or body ""))))

    (when cleanup-after
      (cleanup-docs cleanup-after))

    (when clear-next-feature?
      (spit (doc-path :next-feature) ""))))

(defn run-pipeline [feature-name]
  (let [ctx {:feature-name feature-name}]
    (doseq [stage (:stages *config*)]
      (run-stage stage ctx))))
