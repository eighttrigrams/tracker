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

  Scenario: Overdue task appears in the overdue section
    Given I am on the app
    And a task "Forgotten chore" with due date yesterday exists
    When I navigate to the "Today" tab
    Then I should see "Forgotten chore" in the overdue section

  Scenario: Urgent task appears in the urgent matters section
    Given I am on the app
    And a task "Fix prod bug" with urgency "urgent" exists
    When I navigate to the "Today" tab
    Then I should see "Fix prod bug" in the urgent subsection

  Scenario: Superurgent task appears in the superurgent subsection
    Given I am on the app
    And a task "Server on fire" with urgency "superurgent" exists
    When I navigate to the "Today" tab
    Then I should see "Server on fire" in the superurgent subsection

  Scenario: Meeting with today's start date appears in the today view
    Given I am on the app
    And a meet "Team standup" with start date today exists
    When I click the "Tasks" tab
    And I navigate to the "Today" tab
    Then I should see "Team standup" in the today view

  Scenario: Day selector shows tasks lined up for tomorrow
    Given I am on the app
    And a task "Prepare slides" lined up for tomorrow exists
    When I click the "Tasks" tab
    And I navigate to the "Today" tab
    And I click the second day button
    Then I should see "Prepare slides" in the other things section

  Scenario: Add task via plus button in today view
    Given I am on the app
    And I add a task "Quick note" via the today add button
    Then I should see "Quick note" in the other things section

  Scenario: View switcher toggles to upcoming section
    Given I am on the app
    And a task "Future planning" with due date in 5 days exists
    When I navigate to the "Today" tab
    And I click the "Upcoming" view switcher button
    Then I should see "Future planning" in the upcoming section

  Scenario: Task with due time shows time in today view
    Given I am on the app
    And a task "Morning meeting" with due date today and time "09:30" exists
    When I navigate to the "Today" tab
    Then I should see "09:30" in the today view
