(ns ui.main.lhs.issue-detail
  (:require ["react-markdown$default" :as ReactMarkdown]))

(defn component [*state]
  [:<>
   [:h1 (:title (:selected-issue @*state))]
   (when (:related_issues (:selected-issue @*state))
     [:ul 
      (doall (map (fn [[id title]]
                    [:li 
                     {:key id}
                     title])
                  (:related_issues (:selected-issue @*state))))])
   [:> ReactMarkdown
    {:children (:description (:selected-issue @*state))}]])
