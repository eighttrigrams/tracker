Feature: Task done and undone

  Scenario: User marks a task as done and it appears in done sort mode
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Buy groceries"
    When I expand task "Buy groceries"
    And I click the done button on task "Buy groceries"
    And I switch to sort mode "Done"
    Then I should see "Buy groceries" in the task list

  Scenario: User marks a done task as undone and it no longer appears in done list
    Given I am on the app
    And a done task "Completed chore" exists
    And I click the "Tasks" tab
    When I switch to sort mode "Done"
    Then I should see "Completed chore" in the task list
    When I expand task "Completed chore"
    And I click the undone button on task "Completed chore"
    And I reload the page
    And I click the "Tasks" tab
    Then I should see "Completed chore" in the task list
    And the done tasks API returns no results
