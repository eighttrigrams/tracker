(ns et.tr.ui.core
  (:require [reagent.dom :as rdom]
            [reagent.core :as r]
            [et.tr.ui.state :as state]))

(defn login-form []
  (let [password (r/atom "")]
    (fn []
      [:div.login-form
       [:h2 "Login"]
       (when-let [error (:error @state/app-state)]
         [:div.error error])
       [:input {:type "password"
                :placeholder "Password"
                :value @password
                :on-change #(reset! password (-> % .-target .-value))
                :on-key-down (fn [e]
                               (when (= (.-key e) "Enter")
                                 (state/login @password (fn []
                                                          (reset! password "")
                                                          (state/fetch-items)))))}]
       [:button {:on-click (fn [_]
                             (state/login @password (fn []
                                                      (reset! password "")
                                                      (state/fetch-items))))}
        "Login"]])))

(defn add-item-form []
  (let [title (r/atom "")]
    (fn []
      [:div.add-form
       [:input {:type "text"
                :placeholder "Item title"
                :value @title
                :on-change #(reset! title (-> % .-target .-value))
                :on-key-down #(when (= (.-key %) "Enter")
                                (state/add-item @title (fn [] (reset! title ""))))}]
       [:button {:on-click #(state/add-item @title (fn [] (reset! title "")))}
        "Add"]])))

(defn items-list []
  [:ul.items
   (for [item (:items @state/app-state)]
     ^{:key (:id item)}
     [:li
      [:div.item-title (:title item)]
      [:div.item-date (:created_at item)]])])

(defn app []
  (let [{:keys [auth-required? logged-in?]} @state/app-state]
    [:div
     [:h1 "Tracker"]
     (cond
       (nil? auth-required?)
       [:div "Loading..."]

       (and auth-required? (not logged-in?))
       [login-form]

       :else
       [:div
        (when-let [error (:error @state/app-state)]
          [:div.error error])
        [add-item-form]
        [items-list]])]))

(defn init []
  (state/fetch-auth-required)
  (rdom/render [app] (.getElementById js/document "app"))
  (when (:logged-in? @state/app-state)
    (state/fetch-items)))
