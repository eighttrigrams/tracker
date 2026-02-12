(ns et.tr.telegram
  (:require [et.tr.db :as db]
            [clj-http.client :as http]
            [taoensso.telemere :as tel]))

(defn- telegram-secret []
  (System/getenv "TELEGRAM_WEBHOOK_SECRET"))

(defn- telegram-token []
  (System/getenv "TELEGRAM_BOT_TOKEN"))

(defn- delete-telegram-message [chat-id message-id]
  (when-let [token (telegram-token)]
    (try
      (http/post (str "https://api.telegram.org/bot" token "/deleteMessage")
                 {:form-params {:chat_id chat-id :message_id message-id}
                  :content-type :json})
      (catch Exception e
        (tel/log! :warn (str "Failed to delete Telegram message: " (.getMessage e)))))))

(defn webhook-handler [ds]
  (fn [req]
    (let [secret (telegram-secret)
          provided-secret (get-in req [:headers "x-telegram-bot-api-secret-token"])]
      (cond
        (nil? secret)
        (do
          (tel/log! :warn "No Telegram webhook secret defined")
          {:status 503 :body {:error "Webhook not configured"}})

        (not= secret provided-secret)
        (do
          (tel/log! :warn "Unauthorized Telegram webhook access attempt")
          {:status 403 :body {:error "Unauthorized"}})

        :else
        (let [update (:body req)
              message (or (:message update) (:edited_message update))]
          (if-let [text (:text message)]
            (if (= text "/start")
              {:status 200 :body {:ok true :skipped "start command"}}
              (let [chat-id (get-in message [:chat :id])
                    message-id (:message_id message)
                    title (if (> (count text) 50)
                            (str (subs text 0 47) "...")
                            text)]
                (db/add-message ds nil "Note" title text nil)
                (delete-telegram-message chat-id message-id)
                {:status 200 :body {:ok true}}))
            {:status 200 :body {:ok true :skipped "no text"}}))))))
