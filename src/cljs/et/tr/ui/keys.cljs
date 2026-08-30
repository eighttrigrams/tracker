(ns et.tr.ui.keys
  "The one place that answers \"was that the save combo?\".

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
