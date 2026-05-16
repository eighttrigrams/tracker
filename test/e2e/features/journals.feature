Feature: Journals

  Scenario: User creates a journal and sees it in the list
    Given I am on the app
    And a journal "Work Notes" exists with schedule type "daily"
    When I click the "Resources" tab
    And I click the "Journals" button
    Then I should see "Work Notes" in the journals list
    And I should see "Daily" badge on "Work Notes"

  Scenario: Journals toggle hides importance and sort controls
    Given I am on the app
    And a journal "Daily Log" exists with schedule type "daily"
    When I click the "Resources" tab
    And I click the "Journals" button
    Then the importance filter should not be visible
    And the sort toggle should not be visible

  Scenario: Journals respect scope filter
    Given I am on the app
    And a journal "Private Notes" exists with schedule type "daily" and scope "private"
    When I click the "Resources" tab
    And I click the "Journals" button
    Then I should see "Private Notes" in the journals list
    When I click the "Work" button
    Then I should see "No journals"
