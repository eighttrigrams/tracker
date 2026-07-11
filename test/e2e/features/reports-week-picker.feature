Feature: Reports week(s) picker and resolved issues

  Scenario: Default view shows only the current week with no week divider
    Given I am on the app
    And a done task "This week deed" completed 0 days ago exists
    And a done task "Last week deed" completed 7 days ago exists
    When I click the "Reports" tab
    Then I should see "This week deed" in the reports
    And I should not see "Last week deed" in the reports
    And I should not see a report week divider

  Scenario: Increasing the scope reveals the previous week with a divider
    Given I am on the app
    And a done task "This week deed" completed 0 days ago exists
    And a done task "Last week deed" completed 7 days ago exists
    When I click the "Reports" tab
    And I increase the reports scope
    Then I should see "This week deed" in the reports
    And I should see "Last week deed" in the reports
    And I should see a report week divider

  Scenario: Shifting the From anchor back shows only the older week
    Given I am on the app
    And a done task "This week deed" completed 0 days ago exists
    And a done task "Last week deed" completed 7 days ago exists
    When I click the "Reports" tab
    And I shift the reports From anchor back
    Then I should see "Last week deed" in the reports
    And I should not see "This week deed" in the reports

  Scenario: Resolved issues appear in the report under their resolved day
    Given I am on the app
    And a resolved issue "Fixed the boiler" exists
    When I click the "Reports" tab
    Then I should see "Fixed the boiler" in the reports
    And the report shows a resolved issue card

  Scenario: The Issues and Tasks filter keeps resolved issues visible
    Given I am on the app
    And a resolved issue "Fixed the boiler" exists
    When I click the "Reports" tab
    And I select the "Issues & Tasks" reports filter
    Then I should see "Fixed the boiler" in the reports
