Feature: The work/private switcher reaches the sub-modes

  Recurring tasks and Meeting Series are modes inside the Tasks and Meets tabs
  rather than tabs of their own, so a refetch that dispatches on the active tab
  alone can refetch the wrong collection and leave the sub-mode's list showing
  whatever it loaded when the view opened — under whatever scope was in force
  then. Each scenario therefore asserts the request as well as the list: a stale
  list agrees with the new scope whenever the two happen to coincide, and only
  the request says which collection was refetched at all.

  Scenario: The scope switcher narrows the recurring list
    Given I am on the app
    And a recurring task "Work rhythm" scoped "work" exists
    And a recurring task "Home rhythm" scoped "private" exists
    And I click the "Tasks" tab
    When I click the "Recurring" button
    Then I should see "Work rhythm" in the task list
    And I should see "Home rhythm" in the task list
    When I switch scope to "work"
    Then the last "/api/recurring-tasks" fetch carried context "work"
    And I should see "Work rhythm" in the task list
    And I should not see "Home rhythm" in the task list

  Scenario: Strict mode reaches the recurring list
    Given I am on the app
    And a recurring task "Work rhythm" scoped "work" exists
    And I click the "Tasks" tab
    When I click the "Recurring" button
    And I switch scope to "work"
    Then the last "/api/recurring-tasks" fetch carried strict "false"
    When I toggle strict mode
    Then the last "/api/recurring-tasks" fetch carried strict "true"
    And the last "/api/recurring-tasks" fetch carried context "work"

  Scenario: The scope switcher narrows the meeting series list
    Given I am on the app
    And a meeting series "Work standup" scoped "work" exists
    And a meeting series "Home standup" scoped "private" exists
    And I click the "Meets" tab
    When I click the "Series" button
    Then I should see "Work standup" in the series list
    And I should see "Home standup" in the series list
    When I switch scope to "work"
    Then the last "/api/meeting-series" fetch carried context "work"
    And I should see "Work standup" in the series list
    And I should not see "Home standup" in the series list

  Scenario: Strict mode reaches the meeting series list
    Given I am on the app
    And a meeting series "Work standup" scoped "work" exists
    And I click the "Meets" tab
    When I click the "Series" button
    And I switch scope to "work"
    Then the last "/api/meeting-series" fetch carried strict "false"
    When I toggle strict mode
    Then the last "/api/meeting-series" fetch carried strict "true"
    And the last "/api/meeting-series" fetch carried context "work"

  # The point of routing every one of these through a single dispatch is that it
  # serves the plain views too, so those are proved here rather than assumed.
  Scenario: The plain Tasks view still follows the switcher
    Given I am on the app
    And I click the "Tasks" tab
    When I switch scope to "work"
    Then the last "/api/tasks" fetch carried context "work"
    When I toggle strict mode
    Then the last "/api/tasks" fetch carried strict "true"

  Scenario: The plain Meets view still follows the switcher
    Given I am on the app
    And I click the "Meets" tab
    When I switch scope to "work"
    Then the last "/api/meets" fetch carried context "work"
    When I toggle strict mode
    Then the last "/api/meets" fetch carried strict "true"

  # Today fans out to four lists off one opts map, so it is the case where a
  # mode read a render too late would show up as some lists moving and others
  # not.
  Scenario: The Today fan-out sends the new mode to every one of its lists
    Given I am on the app
    And I click the "Today" tab
    When I switch scope to "work"
    Then the last "/api/tasks" fetch carried context "work"
    And the last "/api/meets" fetch carried context "work"
    And the last "/api/journal-entries/today" fetch carried context "work"
    And the last "/api/issues" fetch carried context "work"
    When I toggle strict mode
    Then the last "/api/tasks" fetch carried strict "true"
    And the last "/api/meets" fetch carried strict "true"
    And the last "/api/journal-entries/today" fetch carried strict "true"
    And the last "/api/issues" fetch carried strict "true"

  # Mail is not a sub-mode case, but it is the one tab whose refetch the shared
  # dispatch had no branch for, so it is the one a consolidation can silently
  # drop.
  Scenario: The Inbox still follows the switcher
    Given I am on the app
    When I click the "Inbox" tab
    And I switch scope to "work"
    Then the last "/api/messages" fetch carried context "work"
    When I toggle strict mode
    Then the last "/api/messages" fetch carried strict "true"
