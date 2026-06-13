Feature: Category badges on the Reports page

  Background:
    Given I am on the app
    And report data with categorized items exists
    And I reload the page

  Scenario: Tasks, journal entries, and meets all show category badges
    When I click the "Reports" tab
    Then I should see "Alice" badge on "Buy paint"
    And I should see "Apollo" badge on "Buy paint"
    And I should see "Alice" badge on "Daily log"
    And I should see "Apollo" badge on "Daily log"
    And I should see "Alice" badge on "Standup"
    And I should see "Apollo" badge on "Standup"

  Scenario: A journal entry on the Reports page can be categorized
    When I click the "Reports" tab
    And I expand the report item "Daily log"
    And I assign the place "Office" to the report item "Daily log"
    Then I should see "Office" badge on "Daily log"
