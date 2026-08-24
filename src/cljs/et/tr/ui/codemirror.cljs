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
            ["@codemirror/view" :refer [EditorView placeholder]]
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
    ;; One layout, shared with blog and personalist — there is no set to choose
    ;; any more. It includes what this namespace used to hold, with one change:
    ;; ctrl+j and ctrl+l are the markdown "sentence" motions rather than line
    ;; start and end. That was tracker's own invention and appears nowhere in the
    ;; scheme's README, so the apps were unified onto the documented behaviour.
    ;;
    ;; install puts a capture-phase listener on the view's element, which is how
    ;; this namespace always did it, so these keys win before CodeMirror's own
    ;; keymaps see the event.
    (ijkl/install view commands)
    view))

(defn get-editor-value [view]
  (when view (.. view -state -doc toString)))

(defn set-editor-value [view value]
  (when view
    (let [transaction
            (.update (.-state view)
                     #js {:changes #js {:from 0 :to (.. view -state -doc -length) :insert value}})]
      (.dispatch view transaction))))

;; ---------------------------------------------------------------------------
;; One-line editors, for input fields
;;
;; The scheme on a <input>, which is a different shape of problem from the
;; description box above and not a smaller one. Three things had to be true at
;; once, and each of them rules out the obvious approach:
;;
;;   it has to look right        tracker styles its inputs almost entirely with
;;                               *element* selectors -- `input { ... }` in
;;                               base.css, then `.mail-add-form input`,
;;                               `.login-form input`, `.item-edit-form input`.
;;                               A <div> wearing the input's classes matches none
;;                               of that, so there is nothing to copy the look
;;                               from by class. What there is, is the input's
;;                               *computed* style -- the resolved answer, however
;;                               the CSS arrived at it.
;;
;;   it has to stay addressable  `state.ui/focus-input!` finds search boxes by
;;                               getElementById and calls .focus(), and the e2e
;;                               suite fills `.edit-item-modal .item-edit-form
;;                               input` and reads .value back off it. A
;;                               contenteditable div has none of that.
;;
;;   it has to stay in the flow  `.search-filter input { flex: 1 }` makes the
;;                               *input* the flex item. Wrap it and the wrapper
;;                               becomes the flex item instead, and the layout
;;                               collapses.
;;
;; All three are answered by not removing the input. It stays exactly where it
;; was, keeps its id and its name, and is made transparent rather than hidden --
;; so the CSS still resolves against it, getElementById still finds it, Playwright
;; still fills it, and the editor is themed from what it computes to. The same
;; trick the library's own fromTextarea uses, and for the same reasons.

(defn- input-theme
  "The editor, dressed as the <input> it stands in front of.

  Read off the live element rather than written out here, because there is no one
  answer to write: the same component is used in a modal, a login form and a card
  header, and `input {}` plus three descendant rules decide what each looks like.
  Whatever the cascade concluded, this copies.

  The focus ring is the exception and has to be guessed, because a computed style
  describes the element as it *is* and `input:focus` has not happened yet. It is
  spelled the way base.css spells it -- accent border, accent glow -- so the two
  agree as long as nobody changes one without the other."
  [input]
  (let [css (js/window.getComputedStyle input)
        g   #(.getPropertyValue css %)]
    (.theme EditorView
            #js {"&" #js {:height (g "height")
                          :boxSizing (g "box-sizing")
                          :fontFamily (g "font-family")
                          :fontSize (g "font-size")
                          :fontWeight (g "font-weight")
                          :letterSpacing (g "letter-spacing")
                          :color (g "color")
                          :backgroundColor (g "background-color")
                          :backdropFilter (g "backdrop-filter")
                          :border (g "border")
                          :borderRadius (g "border-radius")}
                 "&.cm-focused" #js {:outline "none"
                                     :borderColor "var(--accent)"
                                     :boxShadow "0 0 0 3px rgba(0, 113, 227, 0.15)"}
                 ".cm-scroller" #js {:fontFamily (g "font-family")
                                     :lineHeight (g "line-height")
                                     ;; sideways, never down: one line that pans,
                                     ;; which is what an input does with a value
                                     ;; too long for its box.
                                     :overflowX "auto"
                                     :overflowY "hidden"}
                 ".cm-content" #js {:padding (g "padding")
                                    :fontFamily (g "font-family")
                                    :caretColor (g "color")}
                 ".cm-line" #js {:padding "0"}
                 ".cm-gutters" #js {:display "none"}
                 ".cm-activeLine" #js {:backgroundColor "transparent"}
                 ".cm-cursor" #js {:borderLeftColor (g "color")}})))

;; The transparent-and-in-place treatment. Not display:none, and not for a
;; cosmetic reason: a display:none field carrying `required` makes Chrome refuse
;; to submit the form and then refuse to focus the field it is refusing about,
;; which arrives as a button that silently does nothing. Left in place it also
;; keeps a bounding box, which is what Playwright's fill() needs to consider it
;; fillable at all.
(def ^:private hidden-input-style
  #js {:position "absolute"
       :inset "0"
       :width "100%"
       :height "100%"
       :margin "0"
       :opacity "0"
       :pointerEvents "none"})

(defn create-input-editor
  "A one-line editor in `host`, themed from and mirrored onto `input`.

  `input` keeps its place, its id and its name; every change to the document is
  written back into its .value, so anything reading the field the old way still
  reads the truth. Focus landing on it -- a <label for> click, or
  `focus-input!` -- is handed to the editor, and a value written *into* it from
  outside (Playwright's fill(), mainly) is read back into the document.

  Returns the view."
  [host input {:keys [doc on-change]}]
  (let [doc (ijkl/oneLine (or doc ""))
        cm  #js {:EditorState EditorState :EditorView EditorView}
        mirror (.of (.-updateListener EditorView)
                    (fn [^js update]
                      (when (.-docChanged update)
                        (let [text (.. update -state -doc toString)]
                          (set! (.-value input) text)
                          (when on-change (on-change text))))))
        extensions (.concat #js [(input-theme input)]
                            (.concat (ijkl/singleLine cm)
                                     #js [(placeholder (or (.-placeholder input) ""))
                                          mirror]))
        state (.create EditorState #js {:doc doc :extensions extensions})
        view  (new EditorView #js {:state state :parent host})]
    (set! (.-value input) doc)
    (.assign js/Object (.-style input) hidden-input-style)
    ;; The scheme, in its one-line layout: no line motions, no block motions, no
    ;; fenced-Clojure structural editing, and ctrl+j / ctrl+l are line start and
    ;; end. Enter, Escape, Tab and the arrows are in neither layout, so they are
    ;; neither preventDefaulted nor stopped and still reach whatever the app has
    ;; on the field -- which here is "add this item", "clear the filter", and the
    ;; modal's own save-and-close.
    (ijkl/install view commands #js {:mode (.-INPUT ijkl)})
    ;; No focus forwarding from the mirror, and that is a correction rather than
    ;; an omission. Handing focus to the editor the moment the <input> received it
    ;; looked obviously right -- it is what the library's fromTextarea does -- and
    ;; it broke filling the field programmatically. Playwright's fill() focuses
    ;; the element and then inserts text into whatever is focused *now*; with the
    ;; focus already passed on, the insert landed in the editor at caret 0 and
    ;; left the old value sitting behind it, so the field came out as
    ;; "Vim stayedVim stayed...Vim original".
    ;;
    ;; Nothing here needs the forwarding: the mirror is pointer-events:none, so a
    ;; click cannot land on it, and these fields carry no <label for>. The search
    ;; boxes will need an answer -- state.ui/focus-input! finds them by id and
    ;; calls .focus() -- and it will have to be one that a fill() survives.
    ;;
    ;; fill() sets .value and fires `input`; without the listener below the editor
    ;; would never hear about it and the document and the field would disagree.
    (.addEventListener input "input"
                       (fn [_]
                         (let [v (ijkl/oneLine (or (.-value input) ""))]
                           (when-not (= v (.. view -state -doc toString))
                             (set-editor-value view v)
                             (when on-change (on-change v))))))
    view))
