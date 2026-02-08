Feature: Tasks

  Scenario: User can add a task
    Given I am on the app
    When I click the "Tasks" tab
    And I type "My new test task" in the search field
    And I click the add button
    Then I should see "My new test task" in the task list
