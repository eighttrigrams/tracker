Feature: Moving the cursor in a Category picker

  Both pickers — the one on a card and the sidebar's — take Enter on the entry
  under the cursor, or on the top one while the cursor is nowhere. The arrows
  move it, and so does the Cmd cluster: Cmd+I up, Cmd+K down, with j and l left
  and right in the unsaved-changes prompt.

  The card's picker used to act on Enter only once the search had narrowed the
  list to a single entry, and had no cursor at all. The sidebar's had both all
  along; this is the two of them reading one rule.

  Scenario: Enter takes the top entry while the cursor is nowhere
    Given I am on the app
    And a place "Enter Lisbon" exists
    And a place "Enter Lagos" exists
    And I reload the page
    And I click the "Tasks" tab
    And I add a task called "Picker enter task"
    And I expand the task card "Picker enter task"
    When I search "Enter" in the place picker on task "Picker enter task"
    Then the place picker lists more than one place
    When I press Enter in the place picker
    Then the task "Picker enter task" gets picker entry 1

  Scenario: The arrows move the cursor, and Enter takes what it is on
    Given I am on the app
    And a place "Enter Lisbon" exists
    And a place "Enter Lagos" exists
    And I reload the page
    And I click the "Tasks" tab
    And I add a task called "Picker cursor task"
    And I expand the task card "Picker cursor task"
    When I search "Enter" in the place picker on task "Picker cursor task"
    And I move the place picker cursor with "ArrowDown"
    Then the place picker cursor is on entry 1
    When I move the place picker cursor with "ArrowDown"
    Then the place picker cursor is on entry 2
    When I move the place picker cursor with "ArrowUp"
    Then the place picker cursor is on entry 1
    When I move the place picker cursor with "ArrowDown"
    And I press Enter in the place picker
    Then the task "Picker cursor task" gets picker entry 2

  Scenario: Cmd+K and Cmd+I move it the same way
    Given I am on the app
    And a place "Enter Lisbon" exists
    And a place "Enter Lagos" exists
    And I reload the page
    And I click the "Tasks" tab
    And I add a task called "Picker combo task"
    And I expand the task card "Picker combo task"
    When I search "Enter" in the place picker on task "Picker combo task"
    And I move the place picker cursor with "Meta+k"
    And I move the place picker cursor with "Meta+k"
    Then the place picker cursor is on entry 2
    When I move the place picker cursor with "Meta+i"
    Then the place picker cursor is on entry 1
    When I move the place picker cursor with "Meta+k"
    And I press Enter in the place picker
    Then the task "Picker combo task" gets picker entry 2

  Scenario: The cursor holds at the ends rather than wrapping
    Given I am on the app
    And a place "Enter Lisbon" exists
    And a place "Enter Lagos" exists
    And I reload the page
    And I click the "Tasks" tab
    And I add a task called "Picker ends task"
    And I expand the task card "Picker ends task"
    When I search "Enter" in the place picker on task "Picker ends task"
    And I move the place picker cursor with "ArrowUp"
    And I move the place picker cursor with "ArrowUp"
    Then the place picker cursor is on entry 1
    When I move the place picker cursor with "ArrowDown"
    And I move the place picker cursor with "ArrowDown"
    And I move the place picker cursor with "ArrowDown"
    Then the place picker cursor is on entry 2

  Scenario: Enter with nothing left to pick does nothing
    Given I am on the app
    And a place "Enter Lisbon" exists
    And I reload the page
    And I click the "Tasks" tab
    And I add a task called "Picker miss task"
    And I expand the task card "Picker miss task"
    When I search "zzz" in the place picker on task "Picker miss task"
    Then the place picker lists nothing
    When I press Enter in the place picker
    Then the place picker is still open

  # The sidebar's picker already had the arrows; what is new there is the Cmd
  # cluster, which it gets by reading the same rule.
  Scenario: The sidebar's picker takes the Cmd cluster too
    Given I am on the app
    And test data with categorized tasks exists
    And I reload the page
    And I click the "Tasks" tab
    When I open the "places" filter picker
    And I move the sidebar picker cursor with "Meta+k"
    And I move the sidebar picker cursor with "Meta+k"
    Then the sidebar picker cursor is on entry 2
    When I move the sidebar picker cursor with "Meta+i"
    Then the sidebar picker cursor is on entry 1
