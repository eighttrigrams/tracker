(ns et.tr.export
  (:require [clojure.string :as str])
  (:import [java.util.zip ZipOutputStream ZipEntry]
           [java.io ByteArrayOutputStream]
           [java.text Normalizer Normalizer$Form]
           [java.nio.charset StandardCharsets]))

(defn sanitize-filename [s]
  (let [s (or s "")
        normalized (Normalizer/normalize s Normalizer$Form/NFD)
        ascii-only (str/replace normalized #"[\p{M}]" "")
        sanitized (-> ascii-only
                      (str/replace #"\x00" "")
                      (str/replace #"[/\\:*?\"<>|]" "_")
                      (str/replace #"[^\x20-\x7E]" "_")
                      (str/replace #"\s+" "-"))
        final-str (if (str/blank? sanitized) "untitled" sanitized)]
    (subs final-str 0 (min (count final-str) 50))))

(defn- task-to-markdown [task]
  (let [frontmatter (str "---\n"
                         "id: " (:id task) "\n"
                         "created_at: \"" (:created_at task) "\"\n"
                         "modified_at: \"" (:modified_at task) "\"\n"
                         (when (:due_date task) (str "due_date: \"" (:due_date task) "\"\n"))
                         (when (:due_time task) (str "due_time: \"" (:due_time task) "\"\n"))
                         "done: " (if (= 1 (:done task)) "true" "false") "\n"
                         "scope: \"" (:scope task) "\"\n"
                         "importance: \"" (:importance task) "\"\n"
                         "sort_order: " (:sort_order task) "\n"
                         (when (seq (:people task)) (str "people: " (pr-str (:people task)) "\n"))
                         (when (seq (:places task)) (str "places: " (pr-str (:places task)) "\n"))
                         (when (seq (:projects task)) (str "projects: " (pr-str (:projects task)) "\n"))
                         (when (seq (:goals task)) (str "goals: " (pr-str (:goals task)) "\n"))
                         "---\n\n")
        title (str "# " (:title task) "\n\n")
        description (or (:description task) "")]
    (str frontmatter title description)))

(defn create-export-zip [username data]
  (let [baos (ByteArrayOutputStream.)
        timestamp (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd-HHmmss"))]
    (with-open [zos (ZipOutputStream. baos StandardCharsets/UTF_8)]
      (.putNextEntry zos (ZipEntry. "metadata.edn"))
      (.write zos (.getBytes (pr-str {:export_version 1
                                       :exported_at timestamp
                                       :username username}) "UTF-8"))
      (.closeEntry zos)
      (doseq [task (:tasks data)]
        (let [filename (str "tasks/" (:id task) "-" (sanitize-filename (:title task)) ".md")]
          (.putNextEntry zos (ZipEntry. filename))
          (.write zos (.getBytes (task-to-markdown task) "UTF-8"))
          (.closeEntry zos)))
      (.putNextEntry zos (ZipEntry. "people.edn"))
      (.write zos (.getBytes (pr-str (:people data)) "UTF-8"))
      (.closeEntry zos)
      (.putNextEntry zos (ZipEntry. "places.edn"))
      (.write zos (.getBytes (pr-str (:places data)) "UTF-8"))
      (.closeEntry zos)
      (.putNextEntry zos (ZipEntry. "projects.edn"))
      (.write zos (.getBytes (pr-str (:projects data)) "UTF-8"))
      (.closeEntry zos)
      (.putNextEntry zos (ZipEntry. "goals.edn"))
      (.write zos (.getBytes (pr-str (:goals data)) "UTF-8"))
      (.closeEntry zos))
    (.toByteArray baos)))
