Feature: Filter tasks by place

  Scenario: User can filter tasks by place
    Given I am on the app
    When I click the "Categories" tab
    And I add a place called "Test Office"
    And I click the "Tasks" tab
    And I add a task called "Task with place"
    And I add a task called "Task without place"
    And I assign the place "Test Office" to task "Task with place"
    And I filter by place "Test Office"
    Then I should see "Task with place" in the task list
    And I should not see "Task without place" in the task list
