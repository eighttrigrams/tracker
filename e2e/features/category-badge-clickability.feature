Feature: Category badge clickability

  Scenario: Clicking badges activates filters per type
    Given I am on the app
    And test data with categorized tasks exists
    When I click the "Tasks" tab
    Then the "Lagos" badge on task "Fix plumbing" should be clickable
    And the "Renovations" badge on task "Fix plumbing" should be clickable
    And the "Bordeira" badge on task "Paint walls" should be clickable
    When I click the "Lagos" badge on task "Fix plumbing"
    Then the "Renovations" badge on task "Fix plumbing" should be clickable
    And the "Bordeira" badge on task "Paint walls" should not be clickable
