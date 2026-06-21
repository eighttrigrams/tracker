Feature: Maybe meets on the today page

  Scenario: A maybe meet is grayed in the day strip but not in upcoming
    Given I am on the app
    And a meet "Sync" with start date tomorrow exists
    And meet "Sync" is marked as maybe
    When I navigate to the "Today" tab
    And I click the "Upcoming" view switcher button
    Then the meet "Sync" in the upcoming section should not be grayed
    When I click the second day button
    Then the meet "Sync" in the today section should be grayed

  Scenario: Toggling maybe from the meet footer grays it in the strip
    Given I am on the app
    And a meet "Standup" with start date tomorrow exists
    When I navigate to the "Today" tab
    And I click the second day button
    And I expand the today meet "Standup"
    And I toggle maybe on the today meet "Standup"
    Then the meet "Standup" in the today section should be grayed
