Feature: Meet management

  Scenario: User creates a meet and sets a start date
    Given I am on the app
    When I click the "Meets" tab
    And I type "Team standup" in the meets search field
    And I click the "Add" button
    And I clear the meets search field
    Then I should see "Team standup" in the meets list
    When I expand "Team standup" in the meets list
    And I set the start date to 7 days from now on meet "Team standup"
    Then the meet "Team standup" should show a date

  Scenario: Meet appears in upcoming sort mode after setting a future date
    Given I am on the app
    And a meet "Future sync" with start date 7 days from now exists
    When I click the "Meets" tab
    Then I should see "Future sync" in the meets list
