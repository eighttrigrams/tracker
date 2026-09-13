Feature: The in-place title editor

  # Option+click a title and the editor opens over it. For a user with the
  # custom keymap that editor is the scheme's one-line layout, and it was a bare
  # input for a long time — so these two pin the part that was missing rather
  # than the opening of the box, which the older specs already touch.

  Scenario: With the custom keymap the cursor chords move the caret in the in-place editor
    Given I am on the app
    And a user "inliner" with the custom keymap exists
    And I reload the page
    When I switch to the user "inliner"
    And I click the "Tasks" tab
    And I add a task called "Inline original"
    And I option-click the title of task "Inline original"
    Then the in-place title editor should be open
    # Typing straight away proves the caret arrives *after* the title, the way
    # an autofocused input carrying a value does — a caret at 0 would prepend.
    When I type "-end" in the in-place title editor
    Then the in-place title editor should read "Inline original-end"
    When I press the cursor-left chord 4 times
    And I type "X" in the in-place title editor
    Then the in-place title editor should read "Inline originalX-end"

  Scenario: The save combo confirms the in-place title edit
    Given I am on the app
    And a user "comboer" with the custom keymap exists
    And I reload the page
    When I switch to the user "comboer"
    And I click the "Tasks" tab
    And I add a task called "Combo original"
    And I option-click the title of task "Combo original"
    And I type " by combo" in the in-place title editor
    And I press the save combo in the in-place title editor
    Then the in-place title editor should be closed
    And the task "Combo original by combo" should be stored for user "comboer"

  Scenario: Escape leaves the in-place title edit unsaved
    Given I am on the app
    And a user "escaper" with the custom keymap exists
    And I reload the page
    When I switch to the user "escaper"
    And I click the "Tasks" tab
    And I add a task called "Escape original"
    And I option-click the title of task "Escape original"
    And I type " thrown away" in the in-place title editor
    And I press Escape in the in-place title editor
    Then the in-place title editor should be closed
    And the task "Escape original" should be stored for user "escaper"
    And the task "Escape original thrown away" should not be stored for user "escaper"
