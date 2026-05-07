Feature: Today page category filters

  Scenario: Today sidebar shows the standard four sidebar sections
    Given I am on the app
    When I navigate to the "Today" tab
    Then the "Today" sidebar should show the "people" filter section
    And the "Today" sidebar should show the "places" filter section
    And the "Today" sidebar should show the "projects" filter section
    And the "Today" sidebar should show the "goals" filter section

  Scenario: Selecting a place on Today filters today's items
    Given I am on the app
    And the place "Lagos" exists
    And the place "Bordeira" exists
    And a task "Fix plumbing" with due date today exists
    And a task "Paint walls" with due date today exists
    And task "Fix plumbing" is assigned to place "Lagos"
    And task "Paint walls" is assigned to place "Bordeira"
    And I reload the page
    When I navigate to the "Today" tab
    And I filter by place "Lagos"
    Then I should see "Fix plumbing" in the today view
    And I should not see "Paint walls" in the today view

  Scenario: Filter selected on Today persists when switching to Tasks
    Given I am on the app
    And test data with categorized tasks exists
    And I reload the page
    When I navigate to the "Today" tab
    And I filter by project "Renovations"
    And I switch to "Tasks"
    Then the "Renovations" filter should be active in the projects sidebar
    And I should see "Fix plumbing" in the task list
    And I should not see "Paint walls" in the task list

  Scenario: Filter selected on Tasks persists when switching to Today
    Given I am on the app
    And test data with categorized tasks exists
    And I reload the page
    When I click the "Tasks" tab
    And I filter by project "Renovations"
    And I navigate to the "Today" tab
    Then the "Renovations" filter should be active in the projects sidebar

  Scenario: Clicking a category badge on a Today item selects that category
    Given I am on the app
    And the place "Lagos" exists
    And the place "Bordeira" exists
    And a task "Fix plumbing" with due date today exists
    And a task "Paint walls" with due date today exists
    And task "Fix plumbing" is assigned to place "Lagos"
    And task "Paint walls" is assigned to place "Bordeira"
    And I reload the page
    When I navigate to the "Today" tab
    And I click the "Lagos" badge on the today item "Fix plumbing"
    Then the "Lagos" filter should be active in the places sidebar
    And I should see "Fix plumbing" in the today view
    And I should not see "Paint walls" in the today view
