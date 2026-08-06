Feature: Converting an issue into a task

  # The server's 409s are the boundary; these scenarios are about the card not
  # offering a button whose only possible outcome is one of them.

  Scenario: An issue with no tasks converts and arrives on the Tasks page with its badges
    Given I am on the app
    And an issue "Fix the fence" categorised with place "Garden" exists
    And I reload the page
    When I click the "Issues" tab
    And I click the convert button on issue "Fix the fence"
    And I confirm the convert modal
    Then I should not see "Fix the fence" in the issues list
    When I click the "Tasks" tab
    Then I should see "Fix the fence" in the task list
    And the "Garden" badge on task "Fix the fence" should be visible

  Scenario: An issue that has a task offers no conversion
    Given I am on the app
    And a task "Belongs to it" belongs to issue "Has work hanging off it"
    And I reload the page
    When I click the "Issues" tab
    Then the convert button on issue "Has work hanging off it" is not present
    And the create-task button on issue "Has work hanging off it" is present

  Scenario: A resolved issue offers no conversion
    Given I am on the app
    And a resolved issue "Settled long ago" exists
    And I reload the page
    When I click the "Issues" tab
    And I switch the issues sort to "Resolved"
    Then the convert button on issue "Settled long ago" is not present
