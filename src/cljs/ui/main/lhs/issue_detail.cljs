(ns ui.main.lhs.issue-detail
  (:require ["react-markdown$default" :as ReactMarkdown]
            [ui.actions :as actions]))

(defn- issue-links-component [*state related-issues]
  [:ul
   (map (fn [[id title]]
          [:li
           {:key id
            :on-click #(actions/select-issue! *state {:id id})}
           title])
        related-issues)])

(defn component [*state]
  (let [{:keys [selected-issue selected-context]} @*state
        {:keys [title related_issues description]} selected-issue]
    [:<>
     [:h4 (if selected-context (str "[" (:title selected-context) "]") "[Overview]")]
     [:span
      {:style {:font-size "35px"}}
      [:> ReactMarkdown
       {:children title}]]
     (when related_issues
      [issue-links-component *state related_issues])
     [:> ReactMarkdown
      {:children description}]]))
