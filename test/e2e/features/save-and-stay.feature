Feature: Saving without leaving the edit modal

  Scenario: Cmd+Shift+S saves, flashes the checkmark and keeps the modal open
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Stay original"
    And I open the edit modal for task "Stay original"
    And I change the modal title to "Stayed once" and save without closing
    Then the edit modal should still be open
    And the modal title field should show "Stayed once"
    And the task "Stayed once" should be stored
    And the save checkmark should disappear on its own

  Scenario: A second save-and-stay does not conflict with the first
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Twice original"
    And I open the edit modal for task "Twice original"
    And a full second passes
    And I change the modal title to "Saved once" and save without closing
    And I change the modal title to "Saved twice" and save without closing
    Then the conflict banner should not be visible
    And the modal title field should show "Saved twice"
    And the task "Saved twice" should be stored

  Scenario: A save-and-stay that succeeds clears the conflict banner of the one before
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Banner original"
    And I open the edit modal for task "Banner original"
    And the task "Banner original" is changed to "Changed in tab B" out of band
    And I change the modal title to "Stale stay edit" and save without closing, hitting a conflict
    Then the conflict banner should be visible
    And the save checkmark should not be visible
    When I change the modal title to "Recovered stay edit" and save without closing
    Then the conflict banner should not be visible
    And the modal title field should show "Recovered stay edit"
    And the task "Recovered stay edit" should be stored

  Scenario: A save whose date write fails leaves the next save able to succeed
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Half saved"
    And I open the edit modal for task "Half saved"
    And I click the "Time" tab in the modal
    And a full second passes
    And the next due-date write fails once
    And I set the modal due date to today and save without closing, hitting a failed write
    Then the error banner should say "Injected due-date failure"
    And the save checkmark should not be visible
    When I save without closing
    Then the conflict banner should not be visible
    And the task "Half saved" should have its due date set

  Scenario: A save-and-stay that lands after its modal is gone leaves other banners alone
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Outlives its modal"
    And I open the edit modal for task "Outlives its modal"
    And the next content write is held
    And I change the modal title to "Saved after closing" and press the save-and-stay shortcut
    And I press Escape in the modal
    And I discard the unsaved changes
    Then no modal should be open
    When the next task creation fails once
    And I try to add a task called "Unrelated add"
    Then the error banner should say "Injected add failure"
    When the held write lands
    And I type "Saved after closing" in the search field
    Then I should see "Saved after closing" in the task list
    And the error banner should say "Injected add failure"
    And the save checkmark should not be visible
    And the task "Saved after closing" should be stored

  Scenario: A save-and-stay leaves the open form alone
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Tab keeper"
    And I open the edit modal for task "Tab keeper"
    And I click the "Time" tab in the modal
    And a full second passes
    And I save without closing
    Then the "Time" tab in the modal should still be active

  Scenario: Escape right after a save-and-stay does not ask about unsaved changes
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Escape original"
    And I open the edit modal for task "Escape original"
    And I change the modal title to "Escaped clean" and save without closing
    And I press Escape in the modal
    Then no modal should be open
    And I should see "Escaped clean" in the task list

  Scenario: Cmd+S still saves and closes
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Close original"
    And I open the edit modal for task "Close original"
    And I change the modal title to "Closed by save" and save with the keyboard
    Then no modal should be open
    And I should see "Closed by save" in the task list
    And the save checkmark should not be visible

  Scenario: On a confirm modal the shift variant confirms like the plain combo
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Confirmed with shift"
    And I expand task "Confirmed with shift"
    And I open the dropdown on task "Confirmed with shift"
    And I click "Delete" in the dropdown
    Then I see the delete confirmation
    When I press the save-and-stay shortcut
    Then I should not see "Confirmed with shift" in the task list
    And no modal should be open
    And the save checkmark should not be visible

  Scenario: With the custom keymap the save-and-stay combo is Cmd+9
    Given I am on the app
    And a user "vimmer" with the custom keymap exists
    And I reload the page
    When I switch to the user "vimmer"
    And I click the "Tasks" tab
    And I add a task called "Vim original"
    And I open the edit modal for task "Vim original"
    And I change the modal title to "Vim stayed" and save without closing using the custom keymap
    Then the edit modal should still be open
    And the modal title field should show "Vim stayed"
    And the task "Vim stayed" should be stored for user "vimmer"

  Scenario: With the custom keymap Cmd+Esc Cmd+9 saves and closes
    Given I am on the app
    And a user "chorder" with the custom keymap exists
    And I reload the page
    When I switch to the user "chorder"
    And I click the "Tasks" tab
    And I add a task called "Chord original"
    And I open the edit modal for task "Chord original"
    And I change the modal title to "Chord closed" and save and close using the custom keymap
    Then no modal should be open
    And the task "Chord closed" should be stored for user "chorder"

  Scenario: With the custom keymap Cmd+Shift+9 is bound to nothing
    Given I am on the app
    And a user "retiree" with the custom keymap exists
    And I reload the page
    When I switch to the user "retiree"
    And I click the "Tasks" tab
    And I add a task called "Retired original"
    And I open the edit modal for task "Retired original"
    And I change the modal title to "Never saved" and press the retired save-and-stay combo
    Then the edit modal should still be open
    And the save checkmark should not be visible
    And the task "Never saved" should not be stored for user "retiree"

  Scenario: An abandoned save-and-exit prefix does not close the next save
    Given I am on the app
    And a user "abandoner" with the custom keymap exists
    And I reload the page
    When I switch to the user "abandoner"
    And I click the "Tasks" tab
    And I add a task called "Abandoned original"
    And I open the edit modal for task "Abandoned original"
    And I press the save-and-exit prefix and then type in the title
    And I change the modal title to "Still staying" and save without closing using the custom keymap
    Then the edit modal should still be open
    And the task "Still staying" should be stored for user "abandoner"

  Scenario: The unsaved-changes prompt offers discard on the left and keeps editing by default
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Prompt original"
    And I open the edit modal for task "Prompt original"
    And I change the modal title to "Prompt edited" without saving
    And I press Escape in the modal
    Then the unsaved-changes prompt should be open
    And the prompt's choices should read "Discard" then "Go Back"
    And the selected choice should be "Go Back"

  Scenario: Enter on the unsaved-changes prompt keeps editing rather than discarding
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Enter original"
    And I open the edit modal for task "Enter original"
    And I change the modal title to "Enter edited" without saving
    And I press Escape in the modal
    Then the unsaved-changes prompt should be open
    When I press Enter
    Then the edit modal should still be open
    And the modal title field should show "Enter edited"
    And the task "Enter original" should be stored

  Scenario: The select chords move between the prompt's choices
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Chords original"
    And I open the edit modal for task "Chords original"
    And I change the modal title to "Chords edited" without saving
    And I press Escape in the modal
    Then the selected choice should be "Go Back"
    When I press the select-left chord
    Then the selected choice should be "Discard"
    When I press the select-right chord
    Then the selected choice should be "Go Back"
    When I press the select-left chord
    And I press the select-left chord
    Then the selected choice should be "Discard"

  Scenario: Selecting discard and pressing Enter throws the edit away
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Discarded original"
    And I open the edit modal for task "Discarded original"
    And I change the modal title to "Discarded edit" without saving
    And I press Escape in the modal
    Then the unsaved-changes prompt should be open
    When I press the select-left chord
    And I press Enter
    Then no modal should be open
    And the task "Discarded original" should be stored

  Scenario: The save combo does nothing on the unsaved-changes prompt
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Safe original"
    And I open the edit modal for task "Safe original"
    And I change the modal title to "Safe edited" without saving
    And I press Escape in the modal
    Then the unsaved-changes prompt should be open
    When I press the save combo in the prompt
    Then the unsaved-changes prompt should be open
    And the task "Safe original" should be stored

  Scenario: Going back returns the caret to the field it came from
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Caret original"
    And I open the edit modal for task "Caret original"
    And I change the modal title to "Caret edited" without saving
    And I put the caret in the modal title at position 5
    And I press Escape in the modal
    Then the unsaved-changes prompt should be open
    When I press Enter
    Then the edit modal should still be open
    And the caret should be back in the modal title at position 5
    And the modal title field should show "Caret edited"
