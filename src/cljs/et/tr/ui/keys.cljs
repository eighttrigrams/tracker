(ns et.tr.ui.keys
  "The keyboard rules that more than one place needs, each written once.

  So far: \"was that the save combo?\", and \"which way does that move a list
  cursor?\".

  It used to be a private predicate in modals, which was fine while the edit
  modal was the only thing that saved. It is not any more: the combined
  search-add bars enact their Add on it too, on the grounds that a bar with text
  typed into it holds something unsaved just as a form does. Two copies of a
  keyboard rule drift — this is the rule, once.

  Which key it is depends on the user: `Digit9` for the custom keymap, `KeyS`
  otherwise. Both are read off `.-code`, not `.-key`, so a layout that puts
  another character on that key still saves."
  (:require [et.tr.ui.state :as state]))

(defn save-combo?
  "Cmd+9 for custom-keymap users, Cmd+S for everyone else.

  Shift is not excluded here, because what a held shift means differs by caller:
  the edit modal has to tell Cmd+Shift+S apart from Cmd+S, while a search bar has
  no second meaning to protect. Callers that care check `.-shiftKey` themselves."
  [e]
  (and (.-metaKey e)
       (if (state/vim-keys?)
         (= "Digit9" (.-code e))
         (= "KeyS" (.-code e)))))

(def ^:private cursor-codes
  "Cmd+I and Cmd+K as up and down. `i` sits above `k`, and `j` and `l` — left
  and right in the unsaved-changes prompt — flank them, so the four make one
  cursor cluster under the one modifier.

  Read off `.-code`, like everything else here: the physical key is what the
  cluster is, whatever character a layout puts on it."
  {"KeyI" :up
   "KeyK" :down})

(defn cursor-key
  "Which way a keystroke moves a list cursor — :up, :down, or nil for anything
  else.

  The arrows, and the Cmd cluster for hands that would rather not leave the home
  row. One definition for both Category pickers: the sidebar's and the one on a
  card are the same list interaction in two places, and two copies of a keyboard
  rule drift — which is what this namespace exists to stop."
  [e]
  (if (.-metaKey e)
    (cursor-codes (.-code e))
    (case (.-code e)
      "ArrowUp" :up
      "ArrowDown" :down
      nil)))

(defn move-cursor
  "Where a cursor over `n` items goes from `idx`, which is nil while it is
  nowhere yet — the first press in either direction puts it on the top entry,
  which is the one Enter would have taken anyway.

  Both ends hold rather than wrap: a held key must not run off one end and
  reappear at the other, where what sits under the cursor is no longer what the
  eye is following."
  [idx direction n]
  (when (pos? n)
    (if (nil? idx)
      0
      (case direction
        :down (min (inc idx) (dec n))
        :up (max (dec idx) 0)))))
