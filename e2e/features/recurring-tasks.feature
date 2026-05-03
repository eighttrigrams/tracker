Feature: Recurring tasks

  Scenario: User creates a recurring task and generates a task from it
    Given I am on the app
    And a recurring task "Weekly review" exists
    And I click the "Tasks" tab
    When I click the "Recurring" button
    Then I should see "Weekly review" in the task list
    When I expand "Weekly review" in the recurring list
    And I click the edit button on the expanded item
    And I click the "Scheduling" tab in the modal
    And I toggle day "Mon" in the schedule
    And I click "Save" in the modal
    When I expand "Weekly review" in the recurring list
    Then the "Create Task" button for "Weekly review" should be enabled
