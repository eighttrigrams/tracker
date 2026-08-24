(ns et.tr.ui.components.cm-input
  "A single-line field carrying the keyboard scheme, for users who asked for it.

  Deliberately a *drop-in* for `[:input {...}]`: same props, same `:on-change`
  contract, so a call site changes by one token and can be changed back the same
  way. That is worth more than it looks. There are around forty inputs in this
  app and they are not alike -- search-or-add boxes wired to page ratoms, inline
  title editors with commit-on-blur, modal fields whose Enter is handled by a
  document-level listener. A component that asked each of them to be rewritten
  around it would be converted in one big unreviewable sweep; one that asks for a
  token can be converted a field at a time, and each step is its own small diff.

  The gate lives *inside* the component, not at the call sites. `vim-keys?` off
  renders exactly the `[:input]` that was there before -- so the fallback is not
  an approximation of the old behaviour, it is the old behaviour -- and no call
  site has to carry an `if`.

  On the `:on-change` contract. The handlers out there read
  `(-> % .-target .-value)`, and they keep working unchanged, because there is a
  real <input> behind the editor and its .value is written before the handler is
  called. So `.-target` is the actual element, not a fake: `.-value` is right,
  and so is `.-id`, and so is `.blur`. Nothing is being impersonated."
  (:require [reagent.core :as r]
            [et.tr.ui.state :as state]
            [et.tr.ui.codemirror :as cm]))

(defn- editor-props
  "The props the mirrored <input> keeps when an editor is in front of it.

  `:value` and `:on-change` come off, and `:default-value` goes on, because from
  mount onwards the document is the truth and this element is its mirror. Left
  controlled, React would fight every keystroke: it re-renders with the old value
  it still believes in and overwrites what was just typed. Everything else --
  `:id`, `:class`, `:placeholder`, `:type`, `:auto-complete` -- stays, and stays
  on the <input> rather than moving to the wrapper, so that `input {}` in
  base.css and `.item-edit-form input` in modal.css still resolve against it.
  That resolved answer is what the editor is themed from."
  [props value]
  (-> props
      (dissoc :value :on-change :auto-focus)
      (assoc :default-value value)))

(defn cm-input
  [_]
  (let [view  (r/atom nil)
        host  (atom nil)
        field (atom nil)]
    (r/create-class
     {:display-name "cm-input"

      :component-did-mount
      (fn [this]
        (let [{:keys [value on-change auto-focus]} (r/props this)]
          (when (and @host @field (state/vim-keys?))
            (let [v (cm/create-input-editor
                     @host @field
                     {:doc (or value "")
                      ;; The text is handed back as the event shape every existing
                      ;; handler already expects. See the note in the ns docstring:
                      ;; the target is the real element, whose .value the editor has
                      ;; just written.
                      :on-change (fn [_text]
                                   (when on-change
                                     (on-change #js {:target @field})))})]
              (reset! view v)
              (when auto-focus (.focus v))))))

      ;; The parent may reset the field from outside -- Escape clears a filter, a
      ;; modal reopens on another item, a save round-trips. cm-textarea never
      ;; handled that and its document silently drifted from the value it was
      ;; given; this compares and pushes.
      ;;
      ;; Guarded on inequality and not merely on "the props changed", because the
      ;; common re-render is the one *caused* by this editor: typing calls
      ;; on-change, the parent swaps its ratom, and we are re-rendered with the
      ;; value we just produced. Pushing that back would reset the caret to the
      ;; end of the document on every keystroke.
      :component-did-update
      (fn [this _]
        (when-let [v @view]
          (let [value (or (:value (r/props this)) "")]
            (when-not (= value (cm/get-editor-value v))
              (cm/set-editor-value v value)))))

      :component-will-unmount
      (fn [_]
        (when-let [v @view]
          (.destroy v)
          (reset! view nil)))

      :reagent-render
      (fn [props]
        (if-not (state/vim-keys?)
          ;; Untouched: the same element, with the same props, that was here
          ;; before this component existed.
          [:input props]
          ;; The wrapper is positioned only so the <input> can be laid over it;
          ;; it carries no look of its own. The editor draws the box, from what
          ;; the <input> inside computes to.
          [:div.cm-input-host {:style {:position "relative"}
                               :ref #(when % (reset! host %))}
           [:input (assoc (editor-props props (or (:value props) ""))
                          :ref #(when % (reset! field %)))]]))})))
