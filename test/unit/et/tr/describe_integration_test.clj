(ns et.tr.describe-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [et.tr.integration-helpers :refer [with-integration-db GET-json]]))

(use-fixtures :each with-integration-db)

(deftest describe-endpoint-returns-the-api-surface
  (let [{:keys [status body]} (GET-json "/api/describe")]
    (testing "returns 200 with a non-empty JSON array"
      (is (= 200 status))
      (is (sequential? body))
      (is (pos? (count body))))

    (testing "every entry has the rhizome-style shape {:name :ns :arglists :doc}"
      (doseq [entry body]
        (is (string? (:name entry)))
        (is (string? (:ns entry)))
        (is (string? (:arglists entry)))
        (is (string? (:doc entry)))
        (is (seq (:doc entry)))))

    (testing "entries are sorted by ns then name"
      (let [keys-seen (mapv (juxt :ns :name) body)]
        (is (= keys-seen (sort keys-seen)))))

    (testing "meta endpoints (describe + recording-mode toggle) are excluded"
      (let [names (set (map (juxt :ns :name) body))]
        (is (not (contains? names ["et.tr.server" "describe-handler"])))
        (is (not (contains? names ["et.tr.server" "toggle-recording-mode-handler"])))))

    (testing "core handler namespaces are present"
      (let [namespaces (set (map :ns body))]
        (doseq [ns ["et.tr.server.task-handler"
                    "et.tr.server.meet-handler"
                    "et.tr.server.journal-handler"
                    "et.tr.server.message-handler"
                    "et.tr.server.user-handler"
                    "et.tr.server.today-board-handler"]]
          (is (contains? namespaces ns) (str ns " missing from describe")))))

    (testing "every route handler in the namespaces describe knows about is documented"
      (let [route-namespaces ['et.tr.server.task-handler
                              'et.tr.server.category-handler
                              'et.tr.server.message-handler
                              'et.tr.server.resource-handler
                              'et.tr.server.meet-handler
                              'et.tr.server.meeting-series-handler
                              'et.tr.server.recurring-task-handler
                              'et.tr.server.journal-handler
                              'et.tr.server.journal-entry-handler
                              'et.tr.server.relation-handler
                              'et.tr.server.report-handler
                              'et.tr.server.user-handler
                              'et.tr.server.event-handler
                              'et.tr.server.today-board-handler
                              'et.tr.server.source-handler]
            handler-publics (for [ns-sym route-namespaces
                                  [sym _] (ns-publics (find-ns ns-sym))
                                  :when (str/ends-with? (name sym) "-handler")]
                              [(str ns-sym) (str sym)])
            described (set (map (juxt :ns :name) body))]
        (doseq [[ns-str sym-str] handler-publics]
          (is (contains? described [ns-str sym-str])
              (str ns-str "/" sym-str " is a *-handler but missing from describe")))))

    (testing "handler docstrings open with a verb + /api/ path"
      (let [verb-pattern #"^(GET|POST|PUT|DELETE|PATCH)\s+/api/"
            handler-entries (filter #(str/ends-with? (:name %) "-handler") body)
            offenders (remove #(re-find verb-pattern (:doc %)) handler-entries)]
        (is (empty? offenders)
            (str "*-handler docstrings without leading 'VERB /api/...' line: "
                 (mapv (juxt :ns :name) offenders)))))))
