Feature: Reminders

  Scenario: Set a reminder on a task via the dropdown
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Call dentist"
    When I expand task "Call dentist"
    And I open the dropdown on task "Call dentist"
    And I click "Set Reminder" in the dropdown
    And I pick a reminder date 3 days from now
    And I confirm the reminder modal
    Then the task "Call dentist" should have a reminder date set

  Scenario: Active reminder shows Acknowledge Reminder button instead of dropdown
    Given I am on the app
    And a task "Review report" with an active reminder exists
    And I click the "Tasks" tab
    When I expand task "Review report"
    Then I should see the "Acknowledge Reminder" button on task "Review report"
    And I should not see the dropdown button on task "Review report"

  Scenario: Acknowledging a reminder restores the normal dropdown
    Given I am on the app
    And a task "Review report" with an active reminder exists
    And I click the "Tasks" tab
    When I expand task "Review report"
    And I click "Acknowledge Reminder" on task "Review report"
    Then I should see the dropdown button on task "Review report"
    And I should not see the "Acknowledge Reminder" button on task "Review report"

  Scenario: Active reminder appears in the Reminders tab on the Today page
    Given I am on the app
    And a task "Ping supplier" with an active reminder exists
    When I navigate to the "Today" tab
    And I click the "Reminders" view switcher button
    Then I should see "Ping supplier" in the reminders section

  Scenario: Reminders tab shows red indicator when it has items
    Given I am on the app
    And a task "Follow up" with an active reminder exists
    When I navigate to the "Today" tab
    Then the Reminders button should have an indicator

  Scenario: Reminders tab shows no active reminders when empty
    Given I am on the app
    When I navigate to the "Today" tab
    And I click the "Reminders" view switcher button
    Then I should see "No active reminders" in the reminders section

  Scenario: Acknowledging removes task from Reminders tab
    Given I am on the app
    And a task "Renew license" with an active reminder exists
    When I navigate to the "Today" tab
    And I click the "Reminders" view switcher button
    Then I should see "Renew license" in the reminders section
    When I expand reminder task "Renew license"
    And I click "Acknowledge Reminder" on reminder task "Renew license"
    Then I should see "No active reminders" in the reminders section

  Scenario: Collapsing an urgent reminder task acknowledges it
    Given I am on the app
    And a task "Chase invoice" with an active reminder and urgency "urgent" exists
    When I navigate to the "Today" tab
    And I click the "Reminders" view switcher button
    Then I should see "Chase invoice" in the reminders section
    When I expand reminder task "Chase invoice"
    And I collapse reminder task "Chase invoice"
    Then I should see "No active reminders" in the reminders section
    And the task "Chase invoice" should not have an active reminder

  Scenario: Collapsing a superurgent reminder task acknowledges it
    Given I am on the app
    And a task "Fix outage" with an active reminder and urgency "superurgent" exists
    When I navigate to the "Today" tab
    And I click the "Reminders" view switcher button
    Then I should see "Fix outage" in the reminders section
    When I expand reminder task "Fix outage"
    And I collapse reminder task "Fix outage"
    Then I should see "No active reminders" in the reminders section
    And the task "Fix outage" should not have an active reminder

  Scenario: Collapsing a default-urgency reminder task does not acknowledge it
    Given I am on the app
    And a task "Water plants" with an active reminder and urgency "default" exists
    When I navigate to the "Today" tab
    And I click the "Reminders" view switcher button
    Then I should see "Water plants" in the reminders section
    When I expand reminder task "Water plants"
    And I collapse reminder task "Water plants"
    Then I should see "Water plants" in the reminders section
    And the task "Water plants" should still have an active reminder
