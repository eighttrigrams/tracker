Feature: Reports page relations are bidirectional

  Scenario: A task-meet relation shows on both report cards and removing it from one clears both
    Given I am on the app
    And a done report task "Pay Luís" exists
    And a past report meet "OpenBox" exists
    And a relation links report task "Pay Luís" to report meet "OpenBox"
    When I navigate to the "Reports" tab
    And I increase the reports scope
    Then report item "Pay Luís" shows a relation badge for "OpenBox"
    And report item "OpenBox" shows a relation badge for "Pay Luís"
    When I expand report card "OpenBox"
    And I remove the relation badge for "Pay Luís" from report card "OpenBox"
    Then report item "OpenBox" shows no relation badge for "Pay Luís"
    And report item "Pay Luís" shows no relation badge for "OpenBox"
