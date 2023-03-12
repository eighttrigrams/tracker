(ns ui.main.lhs.issue-detail
  (:require ["react-markdown$default" :as ReactMarkdown]
            [ui.actions :as actions]))

(defn- issue-links-component [*state related-issues]
  (when related-issues
    [:<>
     [:h3 "Issues"]
     [:ul
      (map (fn [[id title]]
             [:li
              {:key id
               :on-click #(actions/select-issue! *state {:id id})}
              title])
           related-issues)]]))


(defn- context-links-component [*state related-contexts]
  (when (seq related-contexts)
    [:<>
     [:h3 "Contexts"]
     [:ul
      (map (fn [[id title]]
             [:li
              {:key      id
               :on-click #(actions/select-context! *state {:id id} true)}
              title])
           related-contexts)]]))

(defn component [*state]
  (let [{:keys [selected-issue selected-context]} @*state
        {:keys [title related_issues description contexts]} selected-issue]
    [:<>
     [:h4 (if selected-context (str "[" (:title selected-context) "]") "[Overview]")]
     [context-links-component *state (remove #(= (first %) (:id selected-context)) contexts)] 
     [issue-links-component *state related_issues]
     [:hr]
     [:span
      {:style {:font-size "35px"}}
      [:> ReactMarkdown
       {:children title}]]
     [:> ReactMarkdown
      {:children description}]]))
