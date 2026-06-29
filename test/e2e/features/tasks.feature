Feature: Tasks

  Scenario: User can add a task
    Given I am on the app
    When I click the "Tasks" tab
    And I type "My new test task" in the search field
    And I click the add button
    Then I should see "My new test task" in the task list

  Scenario: Added sort orders by creation time without floating urgent
    Given I am on the app
    And an urgent task "Old urgent" was added earlier
    And a normal task "New normal" was added later
    When I click the "Tasks" tab
    Then the 2nd sort button should read "Added"
    When I click the "Added" sort button
    Then "New normal" should appear above "Old urgent" in the task list
