(ns ui.key-handler.common)

(defn handle-keys* [f]
  (fn [e]
    (let [code          (.-code e)
          ctrl-pressed? (.-ctrlKey e)]
      (f code ctrl-pressed? e))))
