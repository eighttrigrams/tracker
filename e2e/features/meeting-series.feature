Feature: Meeting Series

  Scenario: User can add a meeting series
    Given I am on the app
    And a meeting series "Weekly Standup" exists
    When I click the "Meets" tab
    And I click the "Series" button
    Then I should see "Weekly Standup" in the series list

  Scenario: Series toggle switches between meets and series views
    Given I am on the app
    When I click the "Meets" tab
    Then I should see the sort mode toggle
    When I click the "Series" button
    Then I should not see the sort mode toggle
    When I click the "Series" button
    Then I should see the sort mode toggle

  Scenario: User can set a schedule for a meeting series
    Given I am on the app
    And a meeting series "Daily Sync" exists
    When I click the "Meets" tab
    And I click the "Series" button
    And I expand "Daily Sync" in the series list
    And I click the edit button on the expanded series
    And I click the "Scheduling" tab in the modal
    And I toggle day "Mon" in the schedule
    And I toggle day "Wed" in the schedule
    And I click "Save" in the modal
    Then the series "Daily Sync" should have schedule days "1,3"

  Scenario: Create next meeting button is disabled when a future meeting exists
    Given I am on the app
    And a meeting series "Future Series" exists with a future meeting
    When I click the "Meets" tab
    And I click the "Series" button
    Then the "Create next Meeting" button for "Future Series" should be disabled
