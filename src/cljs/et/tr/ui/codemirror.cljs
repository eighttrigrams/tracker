(ns et.tr.ui.codemirror
  "The editor behind cm-textarea, for users who have vim keys turned on.

  The keyboard scheme is not here any more. It used to be: a 47-chord table and
  eleven hand-written commands, of which rhizome and treina each held a
  near-identical copy. All of that is now `@eighttrigrams/kw-codemirror`, the
  library in the keyboard-wizardry repo that also holds Daniel's VSCode and
  Obsidian keymaps — one implementation of the scheme rather than one per app.
  The chords are unchanged; the library's editingBindings *is* this table, moved.

  What stays here is what is tracker's: the theme, and reporting changes back to
  the caller."
  (:require ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :refer [EditorView]]
            ["@codemirror/commands" :as commands]
            ["@eighttrigrams/kw-codemirror" :as ijkl]))

(defn create-editor [element {:keys [doc on-change]}]
  (let [doc (or doc "")
        minimal-theme
          (.theme EditorView
                  #js {"&" #js {:backgroundColor "var(--glass-bg)"
                                :border "1px solid rgba(0, 0, 0, 0.1)"
                                :borderRadius "10px"
                                :fontFamily "inherit"
                                :fontSize "0.95em"
                                :height "100%"
                                :color "var(--text-primary)"}
                       "&.cm-focused" #js {:outline "none"
                                           :borderColor "var(--accent)"
                                           :boxShadow "0 0 0 3px rgba(0, 113, 227, 0.15)"}
                       ".cm-scroller" #js {:overflow "auto"
                                           :fontFamily "inherit"}
                       ".cm-content" #js {:padding "10px 14px"
                                          :fontFamily "inherit"
                                          :caretColor "var(--text-primary)"}
                       ".cm-line" #js {:padding "0"}
                       ".cm-gutters" #js {:display "none"}
                       ".cm-activeLine" #js {:backgroundColor "transparent"}
                       ".cm-activeLineGutter" #js {:display "none"}
                       ".cm-cursor" #js {:borderLeftColor "var(--text-primary)"}})
        line-wrapping (.-lineWrapping EditorView)
        update-listener (.of (.-updateListener EditorView) (fn [^js update]
                                                             (when (.-docChanged update)
                                                               (when on-change
                                                                 (on-change (.. update -state -doc toString))))))
        extensions #js [minimal-theme line-wrapping update-listener]
        state (.create EditorState #js {:doc doc :extensions extensions})
        view (new EditorView #js {:state state :parent element})]
    ;; The full "Normal editing" set — not the library's default, which is the
    ;; eight markdown motions blog uses. The two disagree about ctrl+j and
    ;; ctrl+l: line start and end here, sentence motions there.
    ;;
    ;; install puts a capture-phase listener on the view's element, which is how
    ;; this namespace always did it, so these keys win before CodeMirror's own
    ;; keymaps see the event.
    (ijkl/install view commands (ijkl/editingBindings commands))
    view))

(defn get-editor-value [view]
  (when view (.. view -state -doc toString)))

(defn set-editor-value [view value]
  (when view
    (let [transaction
            (.update (.-state view)
                     #js {:changes #js {:from 0 :to (.. view -state -doc -length) :insert value}})]
      (.dispatch view transaction))))
