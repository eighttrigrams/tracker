(ns et.tr.db.event
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [clojure.data.json :as json]
            [taoensso.telemere :as tel]
            [et.tr.db :as db]))

(defn- effective-user-id [{:keys [actor-user-id is-machine? parent-user-id]}]
  (if is-machine? parent-user-id actor-user-id))

(defn- ->row [actor {:keys [entity-type entity-id action payload dropped]
                     :or {dropped false}}]
  {:actor_user_id (:actor-user-id actor)
   :actor_username (or (:actor-username actor) "unknown")
   :is_machine (if (:is-machine? actor) 1 0)
   :parent_user_id (when (:is-machine? actor) (:parent-user-id actor))
   :parent_username (when (:is-machine? actor) (:parent-username actor))
   :effective_user_id (effective-user-id actor)
   :entity_type (some-> entity-type name)
   :entity_id entity-id
   :action (name action)
   :payload (json/write-str (or payload {}))
   :dropped (if dropped 1 0)})

(defn record-event!
  "Append a single event row. Never throws — telemere-logs and swallows on
  failure so an event-write cannot break the user-visible write that produced it."
  ([ds actor event-map]
   (record-event! ds nil actor event-map))
  ([ds tx actor event-map]
   (try
     (let [conn (or tx (db/get-conn ds))]
       (jdbc/execute-one! conn
         (sql/format {:insert-into :events
                      :values [(->row actor event-map)]})
         db/jdbc-opts))
     nil
     (catch Exception e
       (tel/log! {:level :warn
                  :data {:err (.getMessage e)
                         :event event-map
                         :actor (select-keys actor [:actor-user-id :actor-username
                                                    :is-machine? :parent-user-id])}}
                 "record-event! failed")
       nil))))

(defn list-events-for-user
  "Return the most recent `limit` events visible to `viewing-user-id`. An event
  is visible when its effective_user_id matches — which already collapses
  machine writes to the parent human at write time."
  ([ds viewing-user-id]
   (list-events-for-user ds viewing-user-id 100))
  ([ds viewing-user-id limit]
   (let [rows (jdbc/execute! (db/get-conn ds)
                (sql/format {:select [:id :ts :actor_user_id :actor_username :is_machine
                                      :parent_user_id :parent_username :effective_user_id
                                      :entity_type :entity_id :action :payload :dropped]
                             :from [:events]
                             :where [:= :effective_user_id viewing-user-id]
                             :order-by [[:ts :desc] [:id :desc]]
                             :limit limit})
                db/jdbc-opts)]
     (mapv (fn [row]
             (-> row
                 (update :payload #(when % (json/read-str % :key-fn keyword)))
                 (update :is_machine #(= 1 %))
                 (update :dropped #(= 1 %))))
           rows))))

(defn diff-fields
  "Given a `before` map and an `after` map, return only the keys whose values
  differ. Both inputs are expected to be unqualified maps."
  [before after]
  (reduce-kv
   (fn [acc k new-v]
     (let [old-v (get before k ::missing)]
       (cond
         (= old-v ::missing) acc
         (= old-v new-v)     acc
         :else               (assoc acc k {:old old-v :new new-v}))))
   {}
   after))
