Feature: Parking the category filter selection

  Option+Escape empties the six sidebar Groups, as it always did, but what came
  out is now parked in a box under them until it is clicked back in or a
  Category is selected anew. With nothing selected and a box on screen the same
  key is the way back, so the gesture is a toggle.

  It only fires while every picker is collapsed — Option+Escape inside an open
  picker still clears just that one Group — which is why the scenarios collapse
  before pressing, and collapse again after any step that expands a picker to
  look inside it.

  Scenario: Option+Escape parks the whole selection and clicking it back restores
    Given I am on the app
    And test data with categorized tasks exists
    And I reload the page
    And I click the "Tasks" tab
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
    And I click the "Tasks" tab
    When I filter by project "Renovations"
    And I collapse the "projects" filter group
    And I press Option+Escape
    Then the parked filter box should show "Renovations"
    When I filter by place "Bordeira"
    Then there should be no parked filter box
    And I should see "Paint walls" in the task list
    And I should not see "Fix plumbing" in the task list

  Scenario: The same key brings the parked selection back, and parks it again
    Given I am on the app
    And test data with categorized tasks exists
    And I reload the page
    And I click the "Tasks" tab
    When I filter by project "Renovations"
    And I collapse the "projects" filter group
    And I press Option+Escape
    Then the parked filter box should show "Renovations"
    When I press Option+Escape
    Then there should be no parked filter box
    And the "projects" filter should show "Renovations" as selected
    And I should see "Fix plumbing" in the task list
    And I should not see "Paint walls" in the task list
    When I collapse the "projects" filter group
    And I press Option+Escape
    Then the parked filter box should show "Renovations"
    And I should see "Paint walls" in the task list

  Scenario: The Inbox has no box, so Option+Escape there leaves the parked selection alone
    Given I am on the app
    And test data with categorized tasks exists
    And I reload the page
    And I click the "Tasks" tab
    When I filter by project "Renovations"
    And I collapse the "projects" filter group
    And I press Option+Escape
    And I click the "Inbox" tab
    And I press Option+Escape
    And I click the "Tasks" tab
    Then the parked filter box should show "Renovations"

  Scenario: The parked selection is the same one on every page
    Given I am on the app
    And test data with categorized tasks exists
    And I reload the page
    And I click the "Tasks" tab
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
    And I click the "Tasks" tab
    When I switch scope to "work"
    And I filter by place "Office"
    And I collapse the "places" filter group
    And I press Option+Escape
    Then the parked filter box should show "Office"
    When I switch scope to "private"
    Then there should be no parked filter box
