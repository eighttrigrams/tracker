(ns et.tr.telegram
  (:require [et.tr.db.task :as db.task]
            [et.tr.db.message :as db.message]
            [et.tr.db.user :as db.user]
            [clj-http.client :as http]
            [clojure.string :as str]
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
              message (or (:message update) (:edited_message update))
              mail-user-id (db.user/get-mail-user-id ds)]
          (if (nil? mail-user-id)
            (do
              (tel/log! :warn "No mail user configured")
              {:status 500 :body {:error "No mail user configured"}})
            (if-let [text (:text message)]
              (if (= text "/start")
                {:status 200 :body {:ok true :skipped "start command"}}
                (let [chat-id (get-in message [:chat :id])
                      message-id (:message_id message)
                      today-match (re-matches #"(?i)(?:today|td|tt)\s+(.*)" text)
                      task-match (re-matches #"(?i)(?:t|task)\s+(.*)" text)]
                  (if (or today-match task-match)
                    (let [task-title (str/trim (second (or today-match task-match)))
                          task (db.task/add-task ds mail-user-id task-title)]
                      (when today-match
                        (db.task/set-task-today ds mail-user-id (:id task) true))
                      (delete-telegram-message chat-id message-id)
                      {:status 200 :body {:ok true :type "task"}})
                    (let [title text]
                      (db.message/add-message ds mail-user-id "Note" title text nil nil nil)
                      (delete-telegram-message chat-id message-id)
                      {:status 200 :body {:ok true}}))))
              {:status 200 :body {:ok true :skipped "no text"}})))))))
