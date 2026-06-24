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

  Scenario: In "no show" mode, expanded cards show read-only badges without edit controls
    Given I am on the app
    And test data with categorized tasks exists
    When I click the "Tasks" tab
    And I click the category badges toggle
    And I expand task "Fix plumbing"
    Then the "Lagos" badge on task "Fix plumbing" should be visible
    And the "Renovations" badge on task "Fix plumbing" should be visible
    And task "Fix plumbing" should not show category add buttons
    And task "Fix plumbing" should not show badge remove buttons

  Scenario: In normal mode, expanded cards show editable badge controls
    Given I am on the app
    And test data with categorized tasks exists
    When I click the "Tasks" tab
    And I expand task "Fix plumbing"
    Then the "Lagos" badge on task "Fix plumbing" should be visible
    And task "Fix plumbing" should show category add buttons
    And task "Fix plumbing" should show badge remove buttons
