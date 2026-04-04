Feature: Today view

  Scenario: Task marked as today appears in the Today tab
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Morning routine"
    When I expand task "Morning routine"
    And I send task "Morning routine" to today
    And I navigate to the "Today" tab
    Then I should see "Morning routine" in the today view

  Scenario: Task with due date today appears in the Today tab
    Given I am on the app
    And a task "Deadline task" with due date today exists
    When I navigate to the "Today" tab
    Then I should see "Deadline task" in the today view
