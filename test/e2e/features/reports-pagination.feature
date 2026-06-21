Feature: Reports week pagination

  Scenario: First window shows only the most recent weeks with a See more button
    Given I am on the app
    And a done task "Recent deed" completed 3 days ago exists
    And a done task "Ancient deed" completed 40 days ago exists
    When I click the "Reports" tab
    Then I should see "Recent deed" in the reports
    And I should not see "Ancient deed" in the reports
    And I should see the reports See more button

  Scenario: See more reveals older weeks and then disappears
    Given I am on the app
    And a done task "Recent deed" completed 3 days ago exists
    And a done task "Ancient deed" completed 40 days ago exists
    When I click the "Reports" tab
    And I click the reports See more button
    Then I should see "Recent deed" in the reports
    And I should see "Ancient deed" in the reports
    And I should not see the reports See more button

  Scenario: Rapid double-clicking See more never skips a week window
    Given I am on the app
    And a done task "Recent deed" completed 3 days ago exists
    And a done task "Middle deed" completed 32 days ago exists
    And a done task "Ancient deed" completed 60 days ago exists
    And reports responses are delayed
    When I click the "Reports" tab
    And I rapid-double-click the reports See more button
    Then I should see "Recent deed" in the reports
    And I should see "Middle deed" in the reports
    And I should see the reports See more button
    When I click the reports See more button
    Then I should see "Ancient deed" in the reports
    And I should not see the reports See more button

  Scenario: A failed See more append never skips the next week window
    Given I am on the app
    And a done task "Recent deed" completed 3 days ago exists
    And a done task "Middle deed" completed 32 days ago exists
    And a done task "Ancient deed" completed 60 days ago exists
    And the first reports See more append fails
    When I click the "Reports" tab
    And I click the reports See more button
    And I click the reports See more button
    Then I should see "Recent deed" in the reports
    And I should see "Middle deed" in the reports
