Feature: Negative category filtering

  Scenario: Shift-clicking a badge hides its items, the rules' targets included
    Given I am on the app
    And test data for negative filtering exists
    When I click the "Tasks" tab
    Then I should see "Seeded task" in the task list
    And I should see "Implied task" in the task list
    And I should see "Plain task" in the task list
    When I shift-click the "Plurama" badge on task "Seeded task"
    Then the sidebar should show "Plurama" as excluded
    And I should not see "Seeded task" in the task list
    And I should not see "Implied task" in the task list
    And I should see "Plain task" in the task list

  Scenario: A category created after page load excludes without a reload
    Given I am on the app
    And a project "Latecomer" on task "Late task" exists
    When I click the "Tasks" tab
    Then I should see "Late task" in the task list
    When I shift-click the "Latecomer" badge on task "Late task"
    Then the sidebar should show "Latecomer" as excluded
    And I should not see "Late task" in the task list

  Scenario: A negative on a category created after page load survives a scope switch
    Given I am on the app
    And a project "Latecomer" on task "Late task" exists
    When I click the "Tasks" tab
    And I shift-click the "Latecomer" badge on task "Late task"
    Then the sidebar should show "Latecomer" as excluded
    When I switch scope to "work"
    Then the sidebar should show "Latecomer" as excluded
    And I should not see "Late task" in the task list
    When I press Option+Escape
    Then I should see "Late task" in the task list

  Scenario: Renaming an excluded category keeps it excluded
    Given I am on the app
    And test data for negative filtering exists
    When I click the "Tasks" tab
    And I shift-click the "Plurama" badge on task "Seeded task"
    Then the sidebar should show "Plurama" as excluded
    And I should not see "Seeded task" in the task list
    When I click the "Categories" button
    And I click the "Projects" category tab
    And I expand the card "Plurama"
    And I click the edit pencil button
    And I change the modal title to "Renamed" and save
    And I click the "Back" button
    Then the sidebar should show "Renamed" as excluded
    And I should not see "Seeded task" in the task list

  Scenario: A plain badge click does nothing while a negative filter is up
    Given I am on the app
    And test data for negative filtering exists
    When I click the "Tasks" tab
    And I shift-click the "Plurama" badge on task "Seeded task"
    And I click the "Other" badge on task "Plain task"
    Then the sidebar should show "Plurama" as excluded
    And I should see "Plain task" in the task list

  Scenario: Option+Escape clears the negative filter and brings the groups back
    Given I am on the app
    And test data for negative filtering exists
    When I click the "Tasks" tab
    And I shift-click the "Plurama" badge on task "Seeded task"
    Then the sidebar should show "Plurama" as excluded
    When I press Option+Escape
    Then the sidebar should show the category filter groups
    And I should see "Seeded task" in the task list
    And I should see "Implied task" in the task list

  Scenario: A chip's x drops just that one negative filter
    Given I am on the app
    And test data for negative filtering exists
    When I click the "Tasks" tab
    And I shift-click the "Plurama" badge on task "Seeded task"
    And I shift-click the "Other" badge on task "Plain task"
    Then the sidebar should show "Plurama" as excluded
    And the sidebar should show "Other" as excluded
    When I remove the excluded chip "Other"
    Then the sidebar should show "Plurama" as excluded
    And I should see "Plain task" in the task list
    And I should not see "Seeded task" in the task list

  Scenario: Shift-click is refused while a positive filter is selected
    Given I am on the app
    And test data for negative filtering exists
    When I click the "Tasks" tab
    And I filter by project "Other"
    And I shift-click the "Home" badge on task "Plain task"
    Then the sidebar should show the category filter groups
    And I should see "Plain task" in the task list

  Scenario: A scope switch drops a negative category the target scope lacks
    Given I am on the app
    And a work-only place "Office" on task "Office task" exists
    When I click the "Tasks" tab
    And I switch scope to "work"
    And I shift-click the "Office" badge on task "Office task"
    Then the sidebar should show "Office" as excluded
    And I should not see "Office task" in the task list
    When I switch scope to "private"
    Then the sidebar should show the category filter groups
