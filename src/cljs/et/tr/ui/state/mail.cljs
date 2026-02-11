(ns et.tr.ui.state.mail
  (:require [ajax.core :refer [GET]]
            [clojure.string :as str]
            [reagent.core :as r]
            [et.tr.ui.api :as api]))

(def ^:const DEFAULT-SENDER "Note")

(defonce *mail-page-state (r/atom {:sort-mode :recent
                                   :expanded-message nil
                                   :fetch-request-id 0
                                   :sender-filter nil
                                   :excluded-senders #{}
                                   :editing-message nil
                                   :confirm-delete-message nil}))

(defn fetch-messages [app-state auth-headers]
  (let [request-id (:fetch-request-id (swap! *mail-page-state update :fetch-request-id inc))
        sort-mode (name (:sort-mode @*mail-page-state))
        sender-filter (:sender-filter @*mail-page-state)
        excluded-senders (:excluded-senders @*mail-page-state)
        url (cond-> (str "/api/messages?sort=" sort-mode)
              sender-filter (str "&sender=" (js/encodeURIComponent sender-filter))
              (seq excluded-senders) (str "&excludedSenders=" (js/encodeURIComponent (str/join "," excluded-senders))))]
    (GET url
      {:response-format :json
       :keywords? true
       :headers (auth-headers)
       :handler (fn [messages]
                  (when (= request-id (:fetch-request-id @*mail-page-state))
                    (swap! app-state assoc :messages messages)))
       :error-handler (fn [_]
                        (when (= request-id (:fetch-request-id @*mail-page-state))
                          (swap! app-state assoc :messages [])))})))

(defn set-mail-sort-mode [app-state auth-headers mode]
  (swap! *mail-page-state assoc :sort-mode mode)
  (fetch-messages app-state auth-headers))

(defn set-expanded-message [id]
  (swap! *mail-page-state assoc :expanded-message id))

(defn set-message-done [app-state auth-headers message-id done?]
  (api/put-json (str "/api/messages/" message-id "/done")
    {:done done?}
    (auth-headers)
    (fn [_] (fetch-messages app-state auth-headers))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update message")))))

(defn set-confirm-delete-message [message]
  (swap! *mail-page-state assoc :confirm-delete-message message))

(defn clear-confirm-delete-message []
  (swap! *mail-page-state assoc :confirm-delete-message nil))

(defn delete-message [app-state auth-headers message-id]
  (api/delete-simple (str "/api/messages/" message-id)
    (auth-headers)
    (fn [_]
      (swap! app-state update :messages
             (fn [messages] (filterv #(not= (:id %) message-id) messages)))
      (clear-confirm-delete-message))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to delete message"))
      (clear-confirm-delete-message))))

(defn set-mail-sender-filter [app-state auth-headers sender]
  (swap! *mail-page-state assoc :sender-filter sender :excluded-senders #{})
  (fetch-messages app-state auth-headers))

(defn clear-mail-sender-filter [app-state auth-headers]
  (swap! *mail-page-state assoc :sender-filter nil)
  (fetch-messages app-state auth-headers))

(defn toggle-excluded-sender [app-state auth-headers sender]
  (swap! *mail-page-state update :excluded-senders
         (fn [excluded]
           (if (contains? excluded sender)
             (disj excluded sender)
             (conj excluded sender))))
  (fetch-messages app-state auth-headers))

(defn clear-excluded-sender [app-state auth-headers sender]
  (swap! *mail-page-state update :excluded-senders disj sender)
  (fetch-messages app-state auth-headers))

(defn set-editing-message [id]
  (swap! *mail-page-state assoc :editing-message id))

(defn clear-editing-message []
  (swap! *mail-page-state assoc :editing-message nil))

(defn reset-mail-page-view-state! []
  (swap! *mail-page-state assoc
         :expanded-message nil
         :editing-message nil))

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
      (clear-editing-message))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to update annotation")))))

(defn add-message [app-state auth-headers title on-success]
  (api/post-json "/api/messages"
    {:sender DEFAULT-SENDER
     :title title
     :description ""}
    (auth-headers)
    (fn [_]
      (fetch-messages app-state auth-headers)
      (when on-success (on-success)))
    (fn [resp]
      (swap! app-state assoc :error (get-in resp [:response :error] "Failed to add message")))))
