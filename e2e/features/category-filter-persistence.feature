Feature: Category filter persistence across tab switches

  Scenario: Filter by place persists when switching to Today and back
    Given I am on the app
    When I click the "Categories" tab
    And I add a place called "Lagos"
    And I click the "Tasks" tab
    And I add a task called "Fix plumbing"
    And I add a task called "Paint walls"
    And I assign the place "Lagos" to task "Fix plumbing"
    And I filter by place "Lagos"
    Then I should see "Fix plumbing" in the task list
    And I should not see "Paint walls" in the task list
    When I switch to "Today"
    And I switch to "Tasks"
    Then I should see "Fix plumbing" in the task list
    And I should not see "Paint walls" in the task list

  Scenario: Filter by project persists when switching to Today and back
    Given I am on the app
    And test data with categorized tasks exists
    And I reload the page
    And I switch to "Tasks"
    And I filter by project "Renovations"
    Then I should see "Fix plumbing" in the task list
    And I should not see "Paint walls" in the task list
    When I switch to "Today"
    And I switch to "Tasks"
    Then I should see "Fix plumbing" in the task list
    And I should not see "Paint walls" in the task list
