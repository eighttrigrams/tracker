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

(defn drop-position
  "Whether a drop landed on the upper or the lower half of the element it was
  dispatched on."
  [e]
  (let [rect (.getBoundingClientRect (.-currentTarget e))
        mid-y (+ (.-top rect) (/ (.-height rect) 2))]
    (if (< (.-clientY e) mid-y) "before" "after")))

(defn make-drag-leave-handler [drag-over-task-id task clear-drag-over-fn]
  (fn [_]
    (when (= drag-over-task-id (:id task))
      (clear-drag-over-fn))))

;; One request, not two: the endpoint gives the item the block's urgency and its
;; position together. Sent apart they are two writers on the same column and the
;; loser decides where the card lands.
(defn make-urgency-task-drop-handler [drag-task-id target-task target-urgency on-drop-fn & [enabled?]]
  (fn [e]
    (when (not= enabled? false)
      (.preventDefault e)
      ;; The block behind the card is a drop target too, and a drop that reached
      ;; both would place the item twice — at the target, then at the end.
      (.stopPropagation e)
      (when (and drag-task-id (not= drag-task-id (:id target-task)))
        (let [rect (.getBoundingClientRect (.-currentTarget e))
              y (.-clientY e)
              mid-y (+ (.-top rect) (/ (.-height rect) 2))
              position (if (< y mid-y) "before" "after")]
          (on-drop-fn drag-task-id target-urgency (:id target-task) position))))))

(defn make-urgency-section-drop-handler [drag-task-id tasks target-urgency on-drop-fn clear-drag-fn & [enabled?]]
  (fn [e]
    (when (not= enabled? false)
      (.preventDefault e)
      (when drag-task-id
        (let [last-task (last tasks)]
          (if (and last-task (not= (:id last-task) drag-task-id))
            (on-drop-fn drag-task-id target-urgency (:id last-task) "after")
            ;; An empty block, or the item is already its last: there is nothing
            ;; to anchor on, so the urgency alone says where it goes.
            (on-drop-fn drag-task-id target-urgency nil nil)))
        (clear-drag-fn)))))
