Feature: Meet over status on the today page

  Scenario: "Meet over" grays the card, clears maybe, and hides the maybe option
    Given I am on the app
    And a meet "OverSync" with start date today exists
    And meet "OverSync" is marked as maybe
    When I navigate to the "Today" tab
    And I expand the today meet "OverSync"
    And I click the meet footer anchor button
    Then the meet "OverSync" in the today section should be grayed because over
    And the meet "OverSync" in the today section should no longer be maybe
    When I open the meet footer dropdown on "OverSync"
    Then the meet footer dropdown shows "Meet not over"
    And the meet footer dropdown does not show "maybe"

  Scenario: "Meet not over" reverts the card and restores the maybe option
    Given I am on the app
    And a meet "OverStandup" with start date today exists
    When I navigate to the "Today" tab
    And I expand the today meet "OverStandup"
    And I click the meet footer anchor button
    Then the meet "OverStandup" in the today section should be grayed because over
    When I open the meet footer dropdown on "OverStandup"
    And I click the footer dropdown item "Meet not over"
    Then the meet "OverStandup" in the today section should no longer be grayed because over
    When I open the meet footer dropdown on "OverStandup"
    Then the meet footer anchor button shows "Meet over"
    And the meet footer dropdown shows "maybe"

  Scenario: The "Meet over" option is absent on a future day, where "Set maybe" is primary
    Given I am on the app
    And a meet "OverTomorrow" with start date tomorrow exists
    When I navigate to the "Today" tab
    And I click the second day button
    And I expand the today meet "OverTomorrow"
    Then the meet footer anchor button shows "Set maybe"
    When I open the meet footer dropdown on "OverTomorrow"
    Then the meet footer dropdown does not show "Meet over"

  Scenario: The "Meet over" option is absent in the upcoming section
    Given I am on the app
    And a meet "OverUpcoming" with start date 7 days from now exists
    When I navigate to the "Today" tab
    And I click the "Upcoming" view switcher button
    And I expand the upcoming meet "OverUpcoming"
    Then the meet footer is a standalone delete button
    And the expanded meet footer does not offer "Meet over"
