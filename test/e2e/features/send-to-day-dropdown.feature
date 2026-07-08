Feature: Send-to-day picker close-on-unmount

  The task footer's send-to-day picker is a :custom footer widget with its own
  open-state, so it does not pass through footer-button. It is wrapped in
  close-on-unmount, so collapsing and re-expanding a task must not show a
  stale-open picker.

  Background:
    Given I am on the app
    And a task "Alpha chore" exists
    And a task "Beta chore" exists
    And I reload the page

  Scenario: The send-to-day picker does not reopen stale after collapse and re-expand
    When I click the "Tasks" tab
    And I expand task "Alpha chore"
    And I open the send-to-day picker on task "Alpha chore"
    Then the send-to-day picker on task "Alpha chore" is open
    When I expand task "Beta chore"
    And I expand task "Alpha chore"
    Then the send-to-day picker on task "Alpha chore" is closed
