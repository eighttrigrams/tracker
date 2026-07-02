Feature: Optimistic concurrency and fetch-on-open

  Scenario: Opening the edit modal shows the current server value
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "Fetch original"
    And the task "Fetch original" is changed to "Fetched fresh value" out of band
    And I open the edit modal for task "Fetch original"
    Then the modal title field should show "Fetched fresh value"

  Scenario: A stale modal save is rejected, reloads, and recovers
    Given I am on the app
    When I click the "Tasks" tab
    And I add a task called "OC original"
    And I open the edit modal for task "OC original"
    And the task "OC original" is changed to "Changed in tab B" out of band
    And I change the modal title to "Stale tab A edit" and save
    Then the conflict banner should be visible
    And the modal title field should show "Changed in tab B"
    And I should not see "Stale tab A edit" in the task list
    When I change the modal title to "Recovered edit" and save
    Then the conflict banner should not be visible
    And I should see "Recovered edit" in the task list
