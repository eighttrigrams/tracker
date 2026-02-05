(ns et.tr.ui.state.mail
  (:require [ajax.core :refer [GET]]
            [et.tr.ui.api :as api]))

(defn fetch-messages [app-state auth-headers]
  (let [request-id (:mail-page/fetch-request-id (swap! app-state update :mail-page/fetch-request-id inc))
        sort-mode (name (:mail-page/sort-mode @app-state))
        sender-filter (:mail-page/sender-filter @app-state)
        url (cond-> (str "/api/messages?sort=" sort-mode)
              sender-filter (str "&sender=" (js/encodeURIComponent sender-filter)))]
    (GET url
      {:response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [messages]
                  (when (= request-id (:mail-page/fetch-request-id @app-state))
                    (swap! app-state assoc :messages messages)))
       :error-handler (fn [_]
                        (when (= request-id (:mail-page/fetch-request-id @app-state))
                          (swap! app-state assoc :messages [])))})))

(defn set-mail-sort-mode [app-state auth-headers mode]
  (swap! app-state assoc :mail-page/sort-mode mode)
  (fetch-messages app-state auth-headers))

(defn set-expanded-message [app-state id]
  (swap! app-state assoc :mail-page/expanded-message id))

(defn set-message-done [app-state auth-headers message-id done?]
  (api/put-json (str "/api/messages/" message-id "/done")
    {:done done?}
    (auth-headers)
    (fn [_] (fetch-messages app-state auth-headers))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update message")))))

(defn set-confirm-delete-message [app-state message]
  (swap! app-state assoc :confirm-delete-message message))

(defn clear-confirm-delete-message [app-state]
  (swap! app-state assoc :confirm-delete-message nil))

(defn delete-message [app-state auth-headers message-id]
  (api/delete-simple (str "/api/messages/" message-id)
    (auth-headers)
    (fn [_]
      (swap! app-state update :messages
             (fn [messages] (filterv #(not= (:id %) message-id) messages)))
      (clear-confirm-delete-message app-state))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete message"))
      (clear-confirm-delete-message app-state))))

(defn set-mail-sender-filter [app-state auth-headers sender]
  (swap! app-state assoc :mail-page/sender-filter sender)
  (fetch-messages app-state auth-headers))

(defn clear-mail-sender-filter [app-state auth-headers]
  (swap! app-state assoc :mail-page/sender-filter nil)
  (fetch-messages app-state auth-headers))

(defn set-editing-message [app-state id]
  (swap! app-state assoc :mail-page/editing-message id))

(defn clear-editing-message [app-state]
  (swap! app-state assoc :mail-page/editing-message nil))

(defn update-message-annotation [app-state auth-headers message-id annotation]
  (api/put-json (str "/api/messages/" message-id "/annotation")
    {:annotation annotation}
    (auth-headers)
    (fn [result]
      (swap! app-state update :messages
             (fn [messages]
               (mapv #(if (= (:id %) message-id)
                        (assoc % :annotation (:annotation result))
                        %)
                     messages)))
      (clear-editing-message app-state))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update annotation")))))
