(ns et.tr.ui.components.drag-drop)

(defn make-drag-start-handler [task set-drag-task-fn]
  (fn [e]
    (.setData (.-dataTransfer e) "text/plain" (str (:id task)))
    (set-drag-task-fn (:id task))))

(defn make-drag-over-handler [task set-drag-over-task-fn]
  (fn [e]
    (.preventDefault e)
    (set-drag-over-task-fn (:id task))))

(defn make-drop-handler [drag-task-id target-task on-drop-fn]
  (fn [e]
    (.preventDefault e)
    (when (and drag-task-id (not= drag-task-id (:id target-task)))
      (let [rect (.getBoundingClientRect (.-currentTarget e))
            y (.-clientY e)
            mid-y (+ (.-top rect) (/ (.-height rect) 2))
            position (if (< y mid-y) "before" "after")]
        (on-drop-fn drag-task-id (:id target-task) position)))))

(defn make-drag-leave-handler [drag-over-task-id task clear-drag-over-fn]
  (fn [_]
    (when (= drag-over-task-id (:id task))
      (clear-drag-over-fn))))
