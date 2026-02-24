(ns et.tr.ui.components.controls
  (:require [et.tr.ui.state :as state]
            [et.tr.i18n :refer [t]]))

(defn- vesicapiscis-icon [selected-scope strict-mode?]
  (let [is-active (= selected-scope "both")
        left-crescent (and (= selected-scope "private") strict-mode?)
        right-crescent (and (= selected-scope "work") strict-mode?)
        left-filled (and (= selected-scope "private") (not strict-mode?))
        right-filled (and (= selected-scope "work") (not strict-mode?))
        both-filled (and (= selected-scope "both") (not strict-mode?))
        intersection-only (and (= selected-scope "both") strict-mode?)
        fill-color (if is-active "white" "var(--accent)")
        stroke-color (if is-active "white" "var(--accent)")
        uid (str (random-uuid))]
    [:svg {:width "32" :height "20" :viewBox "0 0 32 20" :style {:display "inline-block" :vertical-align "middle"}}
     [:defs
      [:mask {:id (str "left-only-" uid)}
       [:rect {:x "0" :y "0" :width "32" :height "20" :fill "white"}]
       [:circle {:cx "21" :cy "10" :r "7" :fill "black"}]]
      [:mask {:id (str "right-only-" uid)}
       [:rect {:x "0" :y "0" :width "32" :height "20" :fill "white"}]
       [:circle {:cx "11" :cy "10" :r "7" :fill "black"}]]]
     (when left-crescent
       [:circle {:cx "11" :cy "10" :r "7" :fill fill-color :stroke "none" :mask (str "url(#left-only-" uid ")")}])
     (when right-crescent
       [:circle {:cx "21" :cy "10" :r "7" :fill fill-color :stroke "none" :mask (str "url(#right-only-" uid ")")}])
     (when left-filled
       [:circle {:cx "11" :cy "10" :r "7" :fill fill-color :stroke "none"}])
     (when right-filled
       [:circle {:cx "21" :cy "10" :r "7" :fill fill-color :stroke "none"}])
     (when both-filled
       [:g
        [:circle {:cx "11" :cy "10" :r "7" :fill fill-color :stroke "none"}]
        [:circle {:cx "21" :cy "10" :r "7" :fill fill-color :stroke "none"}]])
     (when intersection-only
       [:path {:d "M 16 3.5 A 7 7 0 0 1 16 16.5 A 7 7 0 0 1 16 3.5 Z" :fill fill-color :stroke "none"}])
     [:circle {:cx "11" :cy "10" :r "7" :fill "none" :stroke stroke-color :stroke-width "1"}]
     [:circle {:cx "21" :cy "10" :r "7" :fill "none" :stroke stroke-color :stroke-width "1"}]
     (when left-crescent
       [:circle {:cx "21" :cy "10" :r "6.5" :fill "var(--glass-bg)" :stroke "none"}])
     (when right-crescent
       [:circle {:cx "11" :cy "10" :r "6.5" :fill "var(--glass-bg)" :stroke "none"}])]))

(defn scope-toggle [css-class current-value on-change]
  (into [:div {:class css-class}]
        (for [scope ["private" "both" "work"]]
          (if (= scope "both")
            [:button.toggle-option
             {:key scope
              :class (when (= current-value scope) "active")
              :on-click (fn [e]
                          (.stopPropagation e)
                          (if (= current-value "both")
                            (state/toggle-strict-mode)
                            (on-change scope)))}
             [vesicapiscis-icon current-value (:strict-mode @state/*app-state)]]
            [:button.toggle-option
             {:key scope
              :class (when (= current-value scope) "active")
              :on-click (fn [e]
                          (.stopPropagation e)
                          (on-change scope))}
             (t (keyword "toggle" scope))]))))

(defn work-private-toggle []
  (let [mode (name (:work-private-mode @state/*app-state))]
    [scope-toggle "work-private-toggle toggle-group" mode #(state/set-work-private-mode (keyword %))]))

(defn dark-mode-toggle []
  (let [dark-mode (:dark-mode @state/*app-state)]
    [:button.dark-mode-toggle
     {:on-click state/toggle-dark-mode}
     (if dark-mode "\u2600" "\u263E")]))

(defn user-switcher-dropdown []
  (let [available-users (:available-users @state/*app-state)
        current-user (:current-user @state/*app-state)]
    [:div.user-switcher-dropdown
     (doall
      (for [user available-users]
        ^{:key (or (:id user) "admin")}
        [:div.user-switcher-item
         {:class (when (= (:id user) (:id current-user)) "active")
          :on-click #(state/switch-user user)}
         (:username user)]))]))

(defn user-info []
  (let [current-user (:current-user @state/*app-state)
        active-tab (:active-tab @state/*app-state)
        is-admin (state/is-admin?)
        auth-required? (:auth-required? @state/*app-state)
        show-switcher (:show-user-switcher @state/*app-state)]
    (when current-user
      [:div.user-info
       (when is-admin
         [:button.users-btn
          {:class (when (= active-tab :users) "active")
           :on-click #(state/set-active-tab :users)}
          (t :nav/users)])
       (if auth-required?
         [:<>
          [:button.username-btn
           {:class (when (= active-tab :settings) "active")
            :on-click #(state/set-active-tab :settings)}
           (:username current-user)]
          [:button.logout-btn {:on-click state/logout} (t :auth/logout)]]
         [:<>
          [:button.settings-btn
           {:class (when (= active-tab :settings) "active")
            :on-click #(state/set-active-tab :settings)}
           (t :nav/settings)]
          [:div.user-switcher-wrapper
           [:button.switch-user-btn
            {:on-click state/toggle-user-switcher}
            [:span.current-user (:username current-user)]
            [:span.dropdown-arrow (if show-switcher "▲" "▼")]]
           (when show-switcher
             [user-switcher-dropdown])]])])))
