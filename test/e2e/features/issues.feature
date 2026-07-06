Feature: Issues

  Scenario: Issues tab appears in the main navbar and Inbox is a right-side icon
    Given I am on the app
    Then I should see the "Issues" tab in the navbar
    And I should see the Inbox icon

  Scenario: User adds an issue and sees it in the list
    Given I am on the app
    When I click the "Issues" tab
    And I type "Leaky roof" in the issues search field
    And I click the issues add button
    Then I should see "Leaky roof" in the issues list

  Scenario: An issue surfaces a belonging task and the link can be removed
    Given I am on the app
    And a task "Replace tiles" belongs to issue "Roof works"
    When I reload the page
    And I click the "Issues" tab
    And I expand issue "Roof works"
    Then I should see the task "Replace tiles" on issue "Roof works"
    When I unlink the task "Replace tiles" from issue "Roof works"
    Then I should not see the task "Replace tiles" on issue "Roof works"

  Scenario: The Inbox icon navigates to the inbox page
    Given I am on the app
    When I click the Inbox icon
    Then I should see the inbox page
