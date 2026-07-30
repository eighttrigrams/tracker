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

  Scenario: With the custom keymap the save-and-stay combo is Cmd+Shift+9
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
