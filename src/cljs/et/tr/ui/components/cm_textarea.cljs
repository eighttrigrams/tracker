(ns et.tr.ui.components.cm-textarea
  (:require [reagent.core :as r]
            [et.tr.ui.codemirror :as cm]))

(defn cm-textarea [{:keys [value on-change placeholder rows]}]
  (let [editor-view (atom nil)
        container-el (atom nil)]
    (r/create-class
     {:display-name "cm-textarea"

      :component-did-mount
      (fn [_]
        (when-let [el @container-el]
          (let [view (cm/create-editor el {:doc (or @value "")
                                           :on-change (fn [text]
                                                        (when on-change
                                                          (on-change text)))})]
            (reset! editor-view view))))

      :component-will-unmount
      (fn [_]
        (when-let [view @editor-view]
          (.destroy view)
          (reset! editor-view nil)))

      :reagent-render
      (fn [{:keys [rows]}]
        [:div {:ref #(when % (reset! container-el %))
               :style {:flex "1"
                       :overflow-y "auto"
                       :min-height 0}}])})))
