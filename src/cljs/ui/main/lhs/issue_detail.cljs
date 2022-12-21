(ns ui.main.lhs.issue-detail
  (:require ["react-markdown$default" :as ReactMarkdown]))

(defn component [*state]
  [:<>
   [:h1 (:title (:selected-issue @*state))]
   [:> ReactMarkdown
    {:children (:description (:selected-issue @*state))}]])
