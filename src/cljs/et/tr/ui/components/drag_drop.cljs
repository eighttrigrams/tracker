(ns et.tr.ui.components.drag-drop)

(defn make-drag-start-handler [task set-drag-task-fn & [enabled?]]
  (fn [e]
    (when (not= enabled? false)
      (.setData (.-dataTransfer e) "text/plain" (str (:id task)))
      (set-drag-task-fn (:id task)))))

(defn make-drag-over-handler [task set-drag-over-task-fn & [enabled?]]
  (fn [e]
    (when (not= enabled? false)
      (.preventDefault e)
      (set-drag-over-task-fn (:id task)))))

(defn make-drop-handler [drag-task-id target-task on-drop-fn & [enabled?]]
  (fn [e]
    (when (not= enabled? false)
      (.preventDefault e)
      (when (and drag-task-id (not= drag-task-id (:id target-task)))
        (let [rect (.getBoundingClientRect (.-currentTarget e))
              y (.-clientY e)
              mid-y (+ (.-top rect) (/ (.-height rect) 2))
              position (if (< y mid-y) "before" "after")]
          (on-drop-fn drag-task-id (:id target-task) position))))))

(defn make-drag-leave-handler [drag-over-task-id task clear-drag-over-fn]
  (fn [_]
    (when (= drag-over-task-id (:id task))
      (clear-drag-over-fn))))

(defn make-urgency-task-drop-handler [drag-task-id target-task target-urgency ensure-urgency-fn on-drop-fn & [enabled?]]
  (fn [e]
    (when (not= enabled? false)
      (.preventDefault e)
      (when (and drag-task-id (not= drag-task-id (:id target-task)))
        (let [rect (.getBoundingClientRect (.-currentTarget e))
              y (.-clientY e)
              mid-y (+ (.-top rect) (/ (.-height rect) 2))
              position (if (< y mid-y) "before" "after")]
          (ensure-urgency-fn drag-task-id target-urgency)
          (on-drop-fn drag-task-id (:id target-task) position))))))

(defn make-urgency-section-drop-handler [drag-task-id tasks target-urgency ensure-urgency-fn on-drop-fn clear-drag-fn & [enabled?]]
  (fn [e]
    (when (not= enabled? false)
      (.preventDefault e)
      (when drag-task-id
        (ensure-urgency-fn drag-task-id target-urgency)
        (when-let [last-task (last tasks)]
          (when (not= (:id last-task) drag-task-id)
            (on-drop-fn drag-task-id (:id last-task) "after")))
        (clear-drag-fn)))))
