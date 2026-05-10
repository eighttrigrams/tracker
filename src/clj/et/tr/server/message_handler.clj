(ns et.tr.server.message-handler
  (:require [et.tr.server.common :as common]
            [et.tr.server.events :as events]
            [et.tr.db :as db]
            [et.tr.db.message :as db.message]
            [et.tr.db.resource :as db.resource]
            [et.tr.db.task :as db.task]
            [clojure.string :as str]))

(defmacro with-mail-message-context
  [req user-id-sym message-id-sym & body]
  `(if (common/has-mail? ~req)
     (let [~user-id-sym (common/get-user-id ~req)
           ~message-id-sym (Integer/parseInt (get-in ~req [:params :id]))]
       ~@body)
     {:status 403 :body {:error "Mail access required"}}))

(defn list-messages-handler
  "GET /api/messages — list messages for the authenticated user. Query params:
  view (default \"inbox\"), sort (default \"recent\"), sender, context, strict
  (\"true\" enables strict context match), excludedSenders (CSV), importance,
  urgency, q (search term), limit (int — caps the row count; machine users
  default to 10 when omitted). Requires Mail access; returns 403 otherwise."
  [req]
  (if (common/has-mail? req)
    (let [user-id (common/get-user-id req)
          view (keyword (get-in req [:params "view"] "inbox"))
          sort-mode (keyword (get-in req [:params "sort"] "recent"))
          sender (get-in req [:params "sender"])
          context (get-in req [:params "context"])
          strict (= "true" (get-in req [:params "strict"]))
          excluded-senders-param (get-in req [:params "excludedSenders"])
          excluded-senders (when (and excluded-senders-param (not (str/blank? excluded-senders-param)))
                             (set (str/split excluded-senders-param #",")))
          limit (common/parse-int-opt (get-in req [:params "limit"]))]
      {:status 200 :body (db.message/list-messages (common/ensure-ds) user-id {:view view
                                                                                :sort-mode sort-mode
                                                                                :sender-filter sender
                                                                                :excluded-senders excluded-senders
                                                                                :context context
                                                                                :strict strict
                                                                                :importance (get-in req [:params "importance"])
                                                                                :urgency (get-in req [:params "urgency"])
                                                                                :search-term (get-in req [:params "q"])
                                                                                :limit limit})})
    {:status 403 :body {:error "Mail access required"}}))

(defn- validate-message-fields
  [{:keys [sender title type scope importance urgency]}]
  (cond
    (str/blank? sender) "Sender is required"
    (str/blank? title) "Title is required"
    (and (not (str/blank? type))
         (not (#{"text" "markdown" "html"} type)))
    "Type must be one of: text, markdown, html"
    (and (some? scope)
         (not (#{"private" "work"} scope)))
    "Scope must be 'private' or 'work'"
    (and (some? importance)
         (not (db/valid-importances importance)))
    "Importance must be 'normal', 'important', or 'critical'"
    (and (some? urgency)
         (not (db/valid-urgencies urgency)))
    "Urgency must be 'default', 'urgent', or 'superurgent'"))

(defn- task-prefix-match
  "If `title` begins with a `t` or `tt` shortcut prefix (case-insensitive,
  separated from the body by one or more whitespace), returns
  {:today? bool :stripped stripped-title}. Otherwise nil. Lets a caller post
  a plain message and have it land directly as a task — `t` for any task,
  `tt` for one immediately on the today board. Recognised regardless of
  recording mode because POST /api/messages is gate-exempt."
  [title]
  (when (string? title)
    (or (when-let [[_ body] (re-matches #"(?si)tt\s+(.+)" title)]
          {:today? true :stripped (str/trim body)})
        (when-let [[_ body] (re-matches #"(?si)t\s+(.+)" title)]
          {:today? false :stripped (str/trim body)}))))

(defn- create-task-from-prefix!
  [ds user-id {:keys [today? stripped]} {:keys [description scope]}]
  (let [task (db.task/add-task ds user-id stripped (or scope "both"))]
    (when (and (some? description) (not (str/blank? description)))
      (db.task/update-task ds user-id (:id task)
                           {:title stripped :description description :tags ""}))
    (when today?
      (db.task/set-task-today ds user-id (:id task) true))
    task))

(defn- promote-youtube-fields
  "When the user has dropped a YouTube link directly into the inbox (URL
  appears in title or description), rewrite the message so it looks like
  a worker-fed YouTube inbox item: sender=\"YouTube\", title hides the
  video title behind \"New … just dropped\", description carries the
  link. Skipped when the caller already identifies as YouTube (i.e. the
  source worker), so its channel-name override is preserved."
  [{:keys [sender title description] :as fields}]
  (or (when (not= sender "YouTube")
        (when-let [url (or (common/extract-youtube-url title)
                           (common/extract-youtube-url description))]
          (let [{:keys [author]} (or (common/fetch-youtube-oembed url) {})]
            (assoc fields
                   :sender "YouTube"
                   :title (common/build-dropped-title author)
                   :type "markdown"
                   :description url))))
      fields))

(defn add-message!
  "Service-level function for adding a message. Validates inputs, persists
  via db.message/add-message, and records a :create event using `actor`.
  Returns either {:error msg} on validation failure or the created
  message map on success. Called by both the HTTP handler and the
  in-process source worker.

  When `title` carries the shortcut prefix `t` or `tt` (see
  `task-prefix-match`), creates a task directly instead of a message and
  records a :task :create event. The task is returned in place of a
  message map; callers should treat the response as opaque.

  When the message body contains a YouTube URL, the fields are rewritten
  into the canonical worker-fed shape so the user's inbox has a single
  uniform format for YouTube videos regardless of origin."
  [ds actor user-id fields]
  (if-let [error (validate-message-fields fields)]
    {:error error}
    (if-let [match (task-prefix-match (:title fields))]
      (let [task (create-task-from-prefix! ds user-id match fields)]
        (events/record-create-with-actor! ds actor user-id :task (:id task) task)
        task)
      (let [{:keys [sender title description type scope importance urgency]}
            (promote-youtube-fields fields)
            message (db.message/add-message ds user-id sender title description
                                            type scope importance urgency)]
        (events/record-create-with-actor! ds actor user-id :message (:id message) message)
        message))))

(defn add-message-handler
  "POST /api/messages — create a new message. Body fields: sender (required),
  title (required), description, type (\"text\"|\"markdown\"|\"html\"), scope
  (\"private\"|\"work\"), importance, urgency. Validates inputs (400 on
  failure) and records a :create event. Requires Mail access (403 otherwise);
  returns 201 with the created message on success."
  [req]
  (if (common/has-mail? req)
    (let [user-id (common/get-user-id req)
          actor (common/get-actor req)
          result (add-message! (common/ensure-ds) actor user-id (:body req))]
      (if (:error result)
        {:status 400 :body {:success false :error (:error result)}}
        {:status 201 :body result}))
    {:status 403 :body {:error "Mail access required"}}))

(defn set-message-done-handler
  "PUT /api/messages/:id/done — mark a message done or undone. Body field:
  done (boolean, required; 400 if missing). Records an :update event with the
  before/after :done value. Requires Mail access (403 otherwise); 404 when the
  message does not exist for the user."
  [req]
  (if-not (contains? (:body req) :done)
    {:status 400 :body {:error "Missing required field: done"}}
    (with-mail-message-context req user-id message-id
      (let [done? (boolean (get-in req [:body :done]))
            before (events/fetch-fields :messages message-id [:done])
            result (db.message/set-message-done (common/ensure-ds) user-id message-id done?)]
        (if result
          (do (events/record-update! req :message message-id before
                                     (select-keys result [:done]))
              {:status 200 :body result})
          {:status 404 :body {:error "Message not found"}})))))

(defn delete-message-handler
  "DELETE /api/messages/:id — delete a message owned by the user. Snapshots
  the row first and records a :delete event on success. Requires Mail access
  (403 otherwise); returns 404 with {:success false} when the message is not
  found, 200 with {:success true} on success."
  [req]
  (with-mail-message-context req user-id message-id
    (let [snapshot (events/fetch-row :messages message-id)
          result (db.message/delete-message (common/ensure-ds) user-id message-id)]
      (if (:success result)
        (do (events/record-delete! req :message message-id snapshot)
            {:status 200 :body {:success true}})
        {:status 404 :body {:success false :error "Message not found"}}))))

(defn update-message-handler
  "PUT /api/messages/:id — update a message's title and/or description. Body
  fields: title (required, non-blank; 400 otherwise), description. Records an
  :update event diffing the changed fields. Requires Mail access (403); 404
  when the message is not found."
  [req]
  (with-mail-message-context req user-id message-id
    (let [{:keys [title description]} (:body req)]
      (if (str/blank? title)
        {:status 400 :body {:error "Title is required"}}
        (let [before (events/fetch-fields :messages message-id [:title :description])
              result (db.message/update-message (common/ensure-ds) user-id message-id title description)]
          (if result
            (do (events/record-update! req :message message-id before
                                       (select-keys result [:title :description]))
                {:status 200 :body result})
            {:status 404 :body {:error "Message not found"}}))))))

(defn set-message-scope-handler
  "PUT /api/messages/:id/scope — set a message's scope. Body field: scope
  (\"private\", \"work\", or \"both\"; \"both\" is stored as nil). Validates
  the value and returns 400 on an invalid scope. Records an :update event on
  the :scope field. Requires Mail access (403); 404 when not found."
  [req]
  (with-mail-message-context req user-id message-id
    (let [scope (get-in req [:body :scope])]
      (if (and (some? scope) (not (#{"private" "work" "both"} scope)))
        {:status 400 :body {:error "Scope must be 'private', 'work', or 'both'"}}
        (let [before (events/fetch-fields :messages message-id [:scope])
              result (db.message/set-message-scope (common/ensure-ds) user-id message-id
                       (if (= scope "both") nil scope))]
          (if result
            (do (events/record-update! req :message message-id before
                                       (select-keys result [:scope]))
                {:status 200 :body result})
            {:status 404 :body {:error "Message not found"}}))))))

(defn set-message-importance-handler
  "PUT /api/messages/:id/importance — set a message's importance. Body field:
  importance (must be one of db/valid-importances: \"normal\", \"important\",
  \"critical\"; 400 otherwise). Records an :update event on the :importance
  field. Requires Mail access (403); 404 when the message is not found."
  [req]
  (with-mail-message-context req user-id message-id
    (let [importance (get-in req [:body :importance])]
      (if-not (contains? db/valid-importances importance)
        {:status 400 :body {:error "Invalid importance. Must be 'normal', 'important', or 'critical'"}}
        (let [before (events/fetch-fields :messages message-id [:importance])
              result (db.message/set-message-importance (common/ensure-ds) user-id message-id importance)]
          (if result
            (do (events/record-update! req :message message-id before
                                       (select-keys result [:importance]))
                {:status 200 :body result})
            {:status 404 :body {:error "Message not found"}}))))))

(defn set-message-urgency-handler
  "PUT /api/messages/:id/urgency — set a message's urgency. Body field:
  urgency (must be one of db/valid-urgencies: \"default\", \"urgent\",
  \"superurgent\"; 400 otherwise). Records an :update event on the :urgency
  field. Requires Mail access (403); 404 when the message is not found."
  [req]
  (with-mail-message-context req user-id message-id
    (let [urgency (get-in req [:body :urgency])]
      (if-not (contains? db/valid-urgencies urgency)
        {:status 400 :body {:error "Invalid urgency. Must be 'default', 'urgent', or 'superurgent'"}}
        (let [before (events/fetch-fields :messages message-id [:urgency])
              result (db.message/set-message-urgency (common/ensure-ds) user-id message-id urgency)]
          (if result
            (do (events/record-update! req :message message-id before
                                       (select-keys result [:urgency]))
                {:status 200 :body result})
            {:status 404 :body {:error "Message not found"}}))))))

(defn convert-message-to-resource-handler
  "POST /api/messages/:id/convert-to-resource — convert a message into a
  resource pointing at a URL. Body field: link (required, must match
  https?://...; 400 otherwise). For YouTube and Substack URLs, the title is
  fetched via common helpers and passed through. Requires Mail access (403);
  404 when the message is not found."
  [req]
  (with-mail-message-context req user-id message-id
    (let [link (get-in req [:body :link])]
      (if (or (str/blank? link) (not (re-matches #"https?://.*" link)))
        {:status 400 :body {:error "Invalid or missing link URL"}}
        (let [title (cond
                      (common/youtube-url? link) (common/fetch-youtube-title link)
                      (common/substack-url? link) (common/fetch-substack-title link))]
          (if-let [result (db.resource/convert-message-to-resource (common/ensure-ds) user-id message-id link :title title)]
            {:status 200 :body result}
            {:status 404 :body {:error "Message not found"}}))))))

(defn convert-message-to-task-handler
  "POST /api/messages/:id/convert-to-task — convert a message into a task
  belonging to the same user. No body fields are required. Requires Mail
  access (403); 404 when the message is not found, 200 with the new task
  on success."
  [req]
  (with-mail-message-context req user-id message-id
    (if-let [result (db.task/convert-message-to-task (common/ensure-ds) user-id message-id)]
      {:status 200 :body result}
      {:status 404 :body {:error "Message not found"}})))

(defn merge-messages-handler
  "POST /api/messages/:id/merge — merge the message at :id into another
  message. Body field: target-id (required; 400 if missing). Requires Mail
  access (403); returns 404 when either message cannot be found, 200 with
  the merged result otherwise."
  [req]
  (with-mail-message-context req user-id message-id
    (let [target-id (get-in req [:body :target-id])]
      (if (nil? target-id)
        {:status 400 :body {:error "Missing required field: target-id"}}
        (if-let [result (db.message/merge-messages (common/ensure-ds) user-id message-id target-id)]
          {:status 200 :body result}
          {:status 404 :body {:error "Message not found"}})))))
