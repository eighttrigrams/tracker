Feature: Task importance filtering

  Scenario: User filters tasks by importance
    Given I am on the app
    And a task "Normal errand" exists
    And a task "Critical deadline" with importance "critical" exists
    And a task "Important meeting prep" with importance "important" exists
    When I click the "Tasks" tab
    Then I should see "Normal errand" in the task list
    And I should see "Critical deadline" in the task list
    And I should see "Important meeting prep" in the task list
    When I click the critical importance filter
    Then I should see "Critical deadline" in the task list
    And I should not see "Normal errand" in the task list
    And I should not see "Important meeting prep" in the task list
    When I click the important importance filter
    Then I should see "Critical deadline" in the task list
    And I should see "Important meeting prep" in the task list
    And I should not see "Normal errand" in the task list
