Feature: Parking the category filter selection

  Option+Escape empties the six sidebar Groups, as it always did, but what came
  out is now parked in a box under them until it is clicked back in or a
  Category is selected anew. It only fires while every picker is collapsed —
  Option+Escape inside an open picker still clears just that one Group.

  Scenario: Option+Escape parks the whole selection and clicking it back restores
    Given I am on the app
    And test data with categorized tasks exists
    And I reload the page
    And I switch to "Tasks"
    When I filter by place "Lagos"
    And I filter by project "Renovations"
    And I collapse the "projects" filter group
    Then I should see "Fix plumbing" in the task list
    And I should not see "Paint walls" in the task list
    When I press Option+Escape
    Then the parked filter box should show "Lagos"
    And the parked filter box should show "Renovations"
    And nothing should be selected in the "places" filter group
    And nothing should be selected in the "projects" filter group
    And I should see "Fix plumbing" in the task list
    And I should see "Paint walls" in the task list
    When I click the parked filter box
    Then there should be no parked filter box
    And the "places" filter should show "Lagos" as selected
    And the "projects" filter should show "Renovations" as selected
    And I should see "Fix plumbing" in the task list
    And I should not see "Paint walls" in the task list

  Scenario: Selecting a category anew drops the parked selection
    Given I am on the app
    And test data with categorized tasks exists
    And I reload the page
    And I switch to "Tasks"
    When I filter by project "Renovations"
    And I collapse the "projects" filter group
    And I press Option+Escape
    Then the parked filter box should show "Renovations"
    When I filter by place "Bordeira"
    Then there should be no parked filter box
    And I should see "Paint walls" in the task list
    And I should not see "Fix plumbing" in the task list

  Scenario: A second Option+Escape keeps what the first one parked
    Given I am on the app
    And test data with categorized tasks exists
    And I reload the page
    And I switch to "Tasks"
    When I filter by project "Renovations"
    And I collapse the "projects" filter group
    And I press Option+Escape
    And I press Option+Escape
    Then the parked filter box should show "Renovations"

  Scenario: The parked selection is the same one on every page
    Given I am on the app
    And test data with categorized tasks exists
    And I reload the page
    And I switch to "Tasks"
    When I filter by project "Renovations"
    And I collapse the "projects" filter group
    And I press Option+Escape
    And I switch to "Today"
    Then the parked filter box should show "Renovations"
    When I switch to "Issues"
    Then the parked filter box should show "Renovations"
    When I click the parked filter box
    Then there should be no parked filter box
    And the "projects" filter should show "Renovations" as selected

  Scenario: A scope switch drops a parked category the target scope lacks
    Given I am on the app
    And a work-only place "Office" on task "Office task" exists
    And I reload the page
    And I switch to "Tasks"
    When I switch scope to "work"
    And I filter by place "Office"
    And I collapse the "places" filter group
    And I press Option+Escape
    Then the parked filter box should show "Office"
    When I switch scope to "private"
    Then there should be no parked filter box
