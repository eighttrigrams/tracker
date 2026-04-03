(ns et.tr.server.message-handler
  (:require [et.tr.server.common :as common]
            [et.tr.db.message :as db.message]
            [et.tr.db.resource :as db.resource]
            [clojure.string :as str]))

(defmacro with-mail-message-context
  [req user-id-sym message-id-sym & body]
  `(if (common/has-mail? ~req)
     (let [~user-id-sym (common/get-user-id ~req)
           ~message-id-sym (Integer/parseInt (get-in ~req [:params :id]))]
       ~@body)
     {:status 403 :body {:error "Mail access required"}}))

(defn list-messages-handler [req]
  (if (common/has-mail? req)
    (let [user-id (common/get-user-id req)
          sort-mode (keyword (get-in req [:params "sort"] "recent"))
          sender (get-in req [:params "sender"])
          context (get-in req [:params "context"])
          strict (= "true" (get-in req [:params "strict"]))
          excluded-senders-param (get-in req [:params "excludedSenders"])
          excluded-senders (when (and excluded-senders-param (not (str/blank? excluded-senders-param)))
                             (set (str/split excluded-senders-param #",")))]
      {:status 200 :body (db.message/list-messages (common/ensure-ds) user-id {:sort-mode sort-mode
                                                                                :sender-filter sender
                                                                                :excluded-senders excluded-senders
                                                                                :context context
                                                                                :strict strict})})
    {:status 403 :body {:error "Mail access required"}}))

(defn add-message-handler [req]
  (if (common/has-mail? req)
    (let [user-id (common/get-user-id req)
          {:keys [sender title description type scope]} (:body req)]
      (cond
        (str/blank? sender)
        {:status 400 :body {:success false :error "Sender is required"}}

        (str/blank? title)
        {:status 400 :body {:success false :error "Title is required"}}

        (and (not (str/blank? type))
             (not (#{"text" "markdown" "html"} type)))
        {:status 400 :body {:success false :error "Type must be one of: text, markdown, html"}}

        (and (some? scope)
             (not (#{"private" "work"} scope)))
        {:status 400 :body {:success false :error "Scope must be 'private' or 'work'"}}

        :else
        (let [message (db.message/add-message (common/ensure-ds) user-id sender title description type scope)]
          {:status 201 :body message})))
    {:status 403 :body {:error "Mail access required"}}))

(defn set-message-done-handler [req]
  (if-not (contains? (:body req) :done)
    {:status 400 :body {:error "Missing required field: done"}}
    (with-mail-message-context req user-id message-id
      (let [done? (boolean (get-in req [:body :done]))
            result (db.message/set-message-done (common/ensure-ds) user-id message-id done?)]
        (if result
          {:status 200 :body result}
          {:status 404 :body {:error "Message not found"}})))))

(defn delete-message-handler [req]
  (with-mail-message-context req user-id message-id
    (let [result (db.message/delete-message (common/ensure-ds) user-id message-id)]
      (if (:success result)
        {:status 200 :body {:success true}}
        {:status 404 :body {:success false :error "Message not found"}}))))

(defn update-message-annotation-handler [req]
  (with-mail-message-context req user-id message-id
    (let [annotation (get-in req [:body :annotation])
          result (db.message/update-message-annotation (common/ensure-ds) user-id message-id annotation)]
      (if result
        {:status 200 :body result}
        {:status 404 :body {:error "Message not found"}}))))

(defn set-message-scope-handler [req]
  (with-mail-message-context req user-id message-id
    (let [scope (get-in req [:body :scope])]
      (if (and (some? scope) (not (#{"private" "work" "both"} scope)))
        {:status 400 :body {:error "Scope must be 'private', 'work', or 'both'"}}
        (let [result (db.message/set-message-scope (common/ensure-ds) user-id message-id
                       (if (= scope "both") nil scope))]
          (if result
            {:status 200 :body result}
            {:status 404 :body {:error "Message not found"}}))))))

(defn convert-message-to-resource-handler [req]
  (with-mail-message-context req user-id message-id
    (let [link (get-in req [:body :link])]
      (if (or (str/blank? link) (not (re-matches #"https?://.*" link)))
        {:status 400 :body {:error "Invalid or missing link URL"}}
        (let [title (when (common/youtube-url? link) (common/fetch-youtube-title link))]
          (if-let [result (db.resource/convert-message-to-resource (common/ensure-ds) user-id message-id link :title title)]
            {:status 200 :body result}
            {:status 404 :body {:error "Message not found"}}))))))

(defn merge-messages-handler [req]
  (with-mail-message-context req user-id message-id
    (let [target-id (get-in req [:body :target-id])]
      (if (nil? target-id)
        {:status 400 :body {:error "Missing required field: target-id"}}
        (if-let [result (db.message/merge-messages (common/ensure-ds) user-id message-id target-id)]
          {:status 200 :body result}
          {:status 404 :body {:error "Message not found"}})))))
