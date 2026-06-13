Feature: Category badge visibility toggle

  Scenario: Category badges show by default and the eye toggle hides them
    Given I am on the app
    And test data with categorized tasks exists
    When I click the "Tasks" tab
    Then the "Lagos" badge on task "Fix plumbing" should be visible
    When I click the category badges toggle
    Then the "Lagos" badge on task "Fix plumbing" should not be visible
    When I click the category badges toggle
    Then the "Lagos" badge on task "Fix plumbing" should be visible
