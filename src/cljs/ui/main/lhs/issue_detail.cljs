(ns ui.main.lhs.issue-detail
  (:require ["react-markdown$default" :as ReactMarkdown]))

(defn component [*state]
  (let [{:keys [selected-issue selected-context]} @*state
        {:keys [title related_issues description]} selected-issue]
    [:<>
     [:h4 (if selected-context (str "[" (:title selected-context) "]") "[Overview]")]
     [:h1 title]
     (when related_issues
      [:ul 
       (doall (map (fn [[id title]]
                     [:li 
                      {:key id}
                      title])
                   related_issues))])
     [:> ReactMarkdown
      {:children description}]]))
