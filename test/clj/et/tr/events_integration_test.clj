(ns et.tr.events-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [ring.mock.request :as mock]
            [et.tr.auth :as auth]
            [et.tr.db.user :as db.user]
            [et.tr.db.event :as db.event]
            [et.tr.server.common :as common]
            [et.tr.server.recording-mode :as recording-mode]
            [et.tr.integration-helpers :refer [with-integration-db *app* *ds* *user-id*]]))

(use-fixtures :each with-integration-db)

(defn- with-real-auth* [f]
  (with-redefs [common/allow-skip-logins? (constantly false)]
    (f)))

(defmacro with-real-auth [& body]
  `(with-real-auth* (fn [] ~@body)))

(defn- machine-token [machine-id target-id & {:keys [has-mail mail-only]
                                              :or {has-mail false mail-only false}}]
  (auth/create-token {:user-id machine-id
                      :username "machine"
                      :is-admin false
                      :has-mail has-mail
                      :is-machine-user true
                      :for-user-id target-id
                      :mail-only mail-only}))

(defn- API
  [method path {:keys [body token as-admin]}]
  (let [req (cond-> (mock/request method path)
              token                     (mock/header "Authorization" (str "Bearer " token))
              (and (not token)
                   (not as-admin))      (mock/header "X-User-Id" (str *user-id*))
              body                      (-> (mock/header "Content-Type" "application/json")
                                            (mock/body (json/write-str body))))]
    (update (*app* req) :body #(when (seq %) (json/read-str % :key-fn keyword)))))

(defn- ensure-recording-off! []
  (when (recording-mode/enabled?) (recording-mode/toggle!)))

(defn- ensure-recording-on! []
  (when-not (recording-mode/enabled?) (recording-mode/toggle!)))

(defn- create-task! [title]
  (let [resp (API :post "/api/tasks" {:body {:title title}})]
    (is (= 201 (:status resp)) (str "create-task expected 201, got " resp))
    (:body resp)))

(defn- events-for [user-id]
  (db.event/list-events-for-user *ds* user-id))

(defn- only [coll]
  (is (= 1 (count coll)) (str "expected exactly one element, got " (count coll)))
  (first coll))

;; -- entity create/update/delete --------------------------------------------

(deftest human-create-emits-event
  (let [task (create-task! "first")
        evs (events-for *user-id*)
        ev (only evs)]
    (is (= "task" (:entity_type ev)))
    (is (= (:id task) (:entity_id ev)))
    (is (= "create" (:action ev)))
    (is (= *user-id* (:effective_user_id ev)))
    (is (= *user-id* (:actor_user_id ev)))
    (is (false? (:is_machine ev)))
    (is (false? (:dropped ev)))
    (is (= 1 (:version ev)) "events default to schema version 1")
    (is (= "first" (get-in ev [:payload :row :title])))))

(deftest human-update-single-field-emits-flat-payload
  (let [task (create-task! "before")
        before-count (count (events-for *user-id*))
        _ (API :put (str "/api/tasks/" (:id task))
               {:body {:title "after" :description "" :tags ""}})
        evs (events-for *user-id*)
        ev (first evs)]
    (is (= (inc before-count) (count evs)))
    (is (= "update" (:action ev)))
    (is (= "title" (get-in ev [:payload :field])))
    (is (= "before" (get-in ev [:payload :old-value])))
    (is (= "after" (get-in ev [:payload :new-value])))))

(deftest human-update-multi-field-emits-changes-map
  (let [task (create-task! "t")
        before-count (count (events-for *user-id*))
        _ (API :put (str "/api/tasks/" (:id task))
               {:body {:title "t2" :description "new-desc" :tags ""}})
        evs (events-for *user-id*)
        ev (first evs)]
    (is (= (inc before-count) (count evs)))
    (is (= "update" (:action ev)))
    (is (some? (get-in ev [:payload :changes :title])))
    (is (some? (get-in ev [:payload :changes :description])))
    (is (= "t" (get-in ev [:payload :changes :title :old])))
    (is (= "t2" (get-in ev [:payload :changes :title :new])))))

(deftest human-update-noop-emits-no-event
  (let [task (create-task! "stable")
        before-count (count (events-for *user-id*))
        _ (API :put (str "/api/tasks/" (:id task))
               {:body {:title "stable" :description "" :tags ""}})]
    (is (= before-count (count (events-for *user-id*))))))

(deftest delete-emits-snapshot-event
  (let [task (create-task! "doomed")
        _ (API :delete (str "/api/tasks/" (:id task)) {})
        evs (events-for *user-id*)
        del-ev (first (filter #(= "delete" (:action %)) evs))]
    (is (some? del-ev))
    (is (= (:id task) (:entity_id del-ev)))
    (is (= "doomed" (get-in del-ev [:payload :snapshot :title])))))

(deftest setter-emits-flat-update
  (let [task (create-task! "settable")
        before-count (count (events-for *user-id*))
        _ (API :put (str "/api/tasks/" (:id task) "/scope") {:body {:scope "private"}})
        evs (events-for *user-id*)
        ev (first evs)]
    (is (= (inc before-count) (count evs)))
    (is (= "update" (:action ev)))
    (is (= "scope" (get-in ev [:payload :field])))
    (is (= "both" (get-in ev [:payload :old-value])))
    (is (= "private" (get-in ev [:payload :new-value])))))

;; -- machine actor attribution ----------------------------------------------

(deftest machine-write-attributes-machine-but-files-under-parent
  (with-real-auth
    (ensure-recording-on!)
    (try
      (let [machine (db.user/create-user *ds* "mbot" "p"
                                         {:is-machine-user true :for-user-id *user-id*})
            tok (machine-token (:id machine) *user-id*)
            resp (API :post "/api/tasks" {:body {:title "by bot"} :token tok})
            ev (only (events-for *user-id*))]
        (is (= 201 (:status resp)))
        (is (true? (:is_machine ev)))
        (is (= (:id machine) (:actor_user_id ev)))
        (is (= *user-id* (:parent_user_id ev)))
        (is (= *user-id* (:effective_user_id ev)))
        (is (= "create" (:action ev))))
      (finally (ensure-recording-off!)))))

;; -- visibility / scoping ---------------------------------------------------

(deftest events-are-scoped-to-the-effective-user
  (let [other (db.user/create-user *ds* "other" "p")
        _ (create-task! "mine")
        ev (only (events-for *user-id*))]
    (is (= *user-id* (:effective_user_id ev)))
    (is (empty? (events-for (:id other))))))

;; -- /api/events endpoint ---------------------------------------------------

(deftest list-events-endpoint-returns-newest-first
  (let [t1 (create-task! "alpha")
        t2 (create-task! "beta")
        _ (API :put (str "/api/tasks/" (:id t2))
               {:body {:title "beta-2" :description "" :tags ""}})
        resp (API :get "/api/events" {})
        evs (get-in resp [:body :events])]
    (is (= 200 (:status resp)))
    (is (>= (count evs) 3))
    (is (= "update" (:action (first evs))))
    (is (= (:id t2) (:entity_id (first evs))))
    ;; immediately preceding event is t2 create
    (is (= "create" (:action (second evs))))
    (is (= (:id t2) (:entity_id (second evs))))))

(deftest list-events-endpoint-caps-at-100
  (dotimes [_ 5]
    (create-task! "noise"))
  (let [resp (API :get "/api/events?limit=200" {})
        body (:body resp)]
    (is (= 100 (:limit body)))))

;; -- categorize / link events -----------------------------------------------

(deftest categorize-task-emits-link-event
  (let [task (create-task! "for-link")
        person-resp (API :post "/api/people" {:body {:name "Alice"}})
        person-id (:id (:body person-resp))
        before-count (count (events-for *user-id*))
        _ (API :post (str "/api/tasks/" (:id task) "/categorize")
               {:body {:category-type "person" :category-id person-id}})
        evs (events-for *user-id*)
        ev (first evs)]
    (is (< before-count (count evs)))
    (is (= "link" (:action ev)))
    (is (= (:id task) (:entity_id ev)))
    (is (= "person" (get-in ev [:payload :category-type])))
    (is (= person-id (get-in ev [:payload :category-id])))
    (is (= "Alice" (get-in ev [:payload :category-title])))))

;; -- robustness: event-write failure must not break user write --------------

(deftest event-write-failure-does-not-break-user-write
  (with-redefs [db.event/record-event!
                (fn [& _] (throw (RuntimeException. "boom")))]
    (let [resp (API :post "/api/tasks" {:body {:title "should still land"}})]
      (is (= 201 (:status resp)))
      (is (= "should still land" (get-in resp [:body :title]))))))

;; -- dropped machine write events -------------------------------------------

(deftest dropped-write-recording-off-emits-event
  (with-real-auth
    (ensure-recording-off!)
    (let [machine (db.user/create-user *ds* "mbot" "p"
                                       {:is-machine-user true :for-user-id *user-id*})
          tok (machine-token (:id machine) *user-id*)
          before (count (events-for *user-id*))
          resp (API :post "/api/tasks" {:body {:title "x"} :token tok})
          evs (events-for *user-id*)
          ev (first evs)]
      (is (= 200 (:status resp)))
      (is (true? (:dropped (:body resp))))
      (is (= (inc before) (count evs)))
      (is (true? (:dropped ev)))
      (is (= "dropped-write" (:action ev)))
      (is (= "recording-off" (get-in ev [:payload :reason])))
      (is (= "/api/tasks" (get-in ev [:payload :uri])))
      (is (= "post" (get-in ev [:payload :method])))
      (is (true? (:is_machine ev)))
      (is (= (:id machine) (:actor_user_id ev)))
      (is (= *user-id* (:effective_user_id ev))))))

(deftest dropped-write-mail-only-emits-event-with-reason
  (with-real-auth
    (ensure-recording-on!)
    (try
      (let [machine (db.user/create-user *ds* "mailbot" "p"
                                         {:is-machine-user true :for-user-id *user-id*
                                          :mail-only true})
            tok (machine-token (:id machine) *user-id* :has-mail true :mail-only true)
            _ (API :post "/api/tasks" {:body {:title "x"} :token tok})
            ev (->> (events-for *user-id*)
                    (filter #(= "dropped-write" (:action %)))
                    first)]
        (is (some? ev))
        (is (= "mail-only" (get-in ev [:payload :reason])))
        (is (true? (:is_machine ev))))
      (finally (ensure-recording-off!)))))

;; -- recording-mode toggle event --------------------------------------------

(deftest recording-mode-toggle-emits-event-visible-to-everyone
  (let [u2 (db.user/create-user *ds* "u2" "p")
        _ (API :post "/api/recording-mode/toggle" {})
        my-evs (events-for *user-id*)
        u2-evs (events-for (:id u2))
        toggle-ev-mine (first (filter #(= "recording-toggle" (:action %)) my-evs))
        toggle-ev-u2 (first (filter #(= "recording-toggle" (:action %)) u2-evs))]
    (is (some? toggle-ev-mine) "default user sees the toggle event")
    (is (some? toggle-ev-u2) "another user also sees the toggle event")
    (is (boolean? (get-in toggle-ev-mine [:payload :on])))
    (is (= "recording-mode" (:entity_type toggle-ev-mine)))
    (is (nil? (:effective_user_id toggle-ev-mine)))
    (is (nil? (:entity_id toggle-ev-mine))))
  ;; reset
  (ensure-recording-off!))

;; -- user-create / user-delete events ---------------------------------------

(deftest user-create-emits-event
  (let [resp (API :post "/api/users"
                  {:as-admin true
                   :body {:username "new-human" :password "pw"}})
        new-id (:id (:body resp))
        ev (->> (events-for *user-id*)
                (filter #(= "user-create" (:action %)))
                first)]
    (is (= 201 (:status resp)))
    (is (some? ev))
    (is (= new-id (:entity_id ev)))
    (is (= "new-human" (get-in ev [:payload :username])))
    (is (false? (get-in ev [:payload :is_machine])))))

(deftest user-delete-emits-event-with-username
  (let [resp (API :post "/api/users"
                  {:as-admin true
                   :body {:username "doomed-user" :password "pw"}})
        new-id (:id (:body resp))
        _ (API :delete (str "/api/users/" new-id) {:as-admin true})
        ev (->> (events-for *user-id*)
                (filter #(= "user-delete" (:action %)))
                first)]
    (is (some? ev))
    (is (= new-id (:entity_id ev)))
    (is (= "doomed-user" (get-in ev [:payload :username])))))

;; -- relations --------------------------------------------------------------

(deftest add-relation-emits-single-event-with-titles
  (let [t1 (create-task! "src")
        t2 (create-task! "tgt")
        resp (API :post "/api/relations"
                  {:body {:source-type "tsk" :source-id (:id t1)
                          :target-type "tsk" :target-id (:id t2)}})
        ev (->> (events-for *user-id*)
                (filter #(= "relation-add" (:action %)))
                only)]
    (is (= 201 (:status resp)))
    (is (= "relation" (:entity_type ev)))
    (is (= "tsk" (get-in ev [:payload :source :type])))
    (is (= (:id t1) (get-in ev [:payload :source :id])))
    (is (= "src" (get-in ev [:payload :source :title])))
    (is (= (:id t2) (get-in ev [:payload :target :id])))
    (is (= "tgt" (get-in ev [:payload :target :title])))))

(deftest delete-relation-emits-event
  (let [t1 (create-task! "a")
        t2 (create-task! "b")
        _ (API :post "/api/relations"
               {:body {:source-type "tsk" :source-id (:id t1)
                       :target-type "tsk" :target-id (:id t2)}})
        resp (API :delete "/api/relations"
                  {:body {:source-type "tsk" :source-id (:id t1)
                          :target-type "tsk" :target-id (:id t2)}})
        ev (->> (events-for *user-id*)
                (filter #(= "relation-delete" (:action %)))
                first)]
    (is (= 200 (:status resp)))
    (is (some? ev))
    (is (= (:id t1) (get-in ev [:payload :source :id])))))

;; -- multi-entity smoke -----------------------------------------------------

(deftest message-create-emits-event
  (let [resp (API :post "/api/messages"
                  {:body {:sender "tester" :title "ping"}})
        ev (->> (events-for *user-id*)
                (filter #(= "message" (:entity_type %)))
                first)]
    (is (= 201 (:status resp)))
    (is (some? ev))
    (is (= "create" (:action ev)))
    (is (= "ping" (get-in ev [:payload :row :title])))))

(deftest meet-create-emits-event
  (let [resp (API :post "/api/meets" {:body {:title "standup"}})
        ev (->> (events-for *user-id*)
                (filter #(= "meet" (:entity_type %)))
                first)]
    (is (= 201 (:status resp)))
    (is (some? ev))))

(deftest journal-create-emits-event
  (let [resp (API :post "/api/journals" {:body {:title "morning pages"}})
        ev (->> (events-for *user-id*)
                (filter #(= "journal" (:entity_type %)))
                first)]
    (is (= 201 (:status resp)))
    (is (some? ev))))

(deftest resource-create-emits-event
  (let [resp (API :post "/api/resources" {:body {:title "doc"}})
        ev (->> (events-for *user-id*)
                (filter #(= "resource" (:entity_type %)))
                first)]
    (is (= 201 (:status resp)))
    (is (some? ev))))

(deftest person-create-emits-event
  (let [resp (API :post "/api/people" {:body {:name "Bob"}})
        ev (->> (events-for *user-id*)
                (filter #(= "person" (:entity_type %)))
                first)]
    (is (= 201 (:status resp)))
    (is (some? ev))))

(deftest two-machine-users-record-distinct-actor-but-same-effective-user
  (with-real-auth
    (ensure-recording-on!)
    (try
      (let [m1 (db.user/create-user *ds* "bot1" "p"
                                    {:is-machine-user true :for-user-id *user-id*})
            m2 (db.user/create-user *ds* "bot2" "p"
                                    {:is-machine-user true :for-user-id *user-id*})
            t1 (machine-token (:id m1) *user-id*)
            t2 (machine-token (:id m2) *user-id*)
            _ (API :post "/api/tasks" {:body {:title "from-m1"} :token t1})
            _ (API :post "/api/tasks" {:body {:title "from-m2"} :token t2})
            evs (->> (events-for *user-id*)
                     (filter #(and (= "task" (:entity_type %)) (= "create" (:action %)))))
            actor-ids (set (map :actor_user_id evs))]
        (is (>= (count evs) 2))
        (is (contains? actor-ids (:id m1)))
        (is (contains? actor-ids (:id m2)))
        ;; both events filed under the parent user
        (is (every? #(= *user-id* (:effective_user_id %)) evs)))
      (finally (ensure-recording-off!)))))

(deftest other-user-cannot-see-my-entity-events-but-sees-system-events
  (let [u2 (db.user/create-user *ds* "u2" "p")
        _ (create-task! "private")
        _ (API :post "/api/recording-mode/toggle" {})
        my-evs (events-for *user-id*)
        u2-evs (events-for (:id u2))]
    (is (some #(and (= "task" (:entity_type %)) (= "create" (:action %))) my-evs))
    (is (not-any? #(and (= "task" (:entity_type %)) (= "create" (:action %))) u2-evs))
    (is (some #(= "recording-toggle" (:action %)) u2-evs))
    ;; reset
    (ensure-recording-off!)))

(deftest version-field-present-on-stored-events
  (create-task! "vt")
  (let [resp (API :get "/api/events" {})
        evs (get-in resp [:body :events])]
    (is (every? #(= 1 (:version %)) evs))))
