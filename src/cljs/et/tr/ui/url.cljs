(ns et.tr.ui.url)

(def ^:private type->prefix
  {:task "tsk"
   :resource "res"
   :meet "met"})

(def ^:private prefix->type
  {"tsk" :task
   "res" :resource
   "met" :meet})

(def ^:private prefix->api-path
  {"tsk" "/api/tasks/"
   "res" "/api/resources/"
   "met" "/api/meets/"})

(defn parse-item-path [pathname]
  (when-let [[_ slug] (re-matches #"/item/(\w+)" pathname)]
    (let [prefix (subs slug 0 (min 3 (count slug)))
          id-str (subs slug 3)]
      (when-let [entity-type (prefix->type prefix)]
        (let [id (js/parseInt id-str 10)]
          (when-not (js/isNaN id)
            {:type entity-type
             :id id
             :api-path (str (prefix->api-path prefix) id)}))))))

(defn entity->path [{:keys [type entity]}]
  (when-let [prefix (type->prefix type)]
    (str "/item/" prefix (:id entity))))

(defn push-state! [path]
  (.pushState js/history nil "" path))

(defn replace-state! [path]
  (.replaceState js/history nil "" path))
