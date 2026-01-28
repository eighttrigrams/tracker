When switching Tabs, for example between Today and Tasks,
all Task cards should be in collapsed state.

## Implementation Notes

To collapse cards on tab switch, each page needs its own expanded-task state. The previous attempt renamed `:expanded-task` to `:tasks-page/expanded-task` and added `:today-page/expanded-task` in state.cljs, but the UI components still read from `:expanded-task`. Since the UI reads the old key (always nil), tasks never appear expanded. Update the UI to use the page-specific keys.

---

## Boyscout Guidance

Since this feature is small, use the saved cycles to give the boyscout extra room for cleanup. During the boyscout phase, go beyond the minimum - look for nearby code smells, minor refactorings, and small improvements in the areas you touch. Don't go overboard, but make good use of the time.
