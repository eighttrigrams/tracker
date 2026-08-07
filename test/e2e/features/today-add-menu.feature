Feature: The Today page's plus offers a Task or a Meet

  # There is one plus button, not five. The five day buttons select an offset of
  # 0 to 4 into one section, so every scenario below drives the same button and
  # differs only in which day it is showing.
  #
  # The menu opens on hover, the way the Tasks page's sort selector does. There
  # is no click to open it, so there is nothing here that clicks it open.

  Scenario: Hovering the plus opens the Task/Meet menu
    Given I am on the app
    When I navigate to the "Today" tab
    And I hover the today add button
    Then the today add menu offers a Task and a Meet

  # The property the whole thing rests on, and the one that is easy to lose by
  # rewriting rather than copying: the mouse-enter/leave handlers belong on the
  # wrapper and not on the button, with the menu absolutely positioned inside
  # that wrapper. Put them on the button and leaving it closes the menu, so the
  # pointer can never arrive at an option — which a test that clicks faster than
  # reagent re-renders would not notice, hence the dwell in the step.
  Scenario: The menu survives the pointer travelling from the plus onto it
    Given I am on the app
    When I navigate to the "Today" tab
    And I hover the today add button
    And I move the pointer onto the Meet option
    Then the today add menu offers a Task and a Meet

  Scenario: The menu closes again when the pointer leaves
    Given I am on the app
    When I navigate to the "Today" tab
    And I hover the today add button
    And I move the pointer off the today add button
    Then the today add menu is not shown
    And the today add button is shown

  Scenario: Neither the plus nor its open menu appears in relation mode
    Given I am on the app
    When I navigate to the "Today" tab
    And I hover the today add button
    And I activate relation mode
    Then the today add menu is not shown
    And the today add button is not shown

  # A meet has no today flag, only a date, so offset 0 is not a special case
  # here the way it is for a task — it is the day the page is showing like any
  # other.
  #
  # Reading start_date back is not enough to prove that on offset 0, and this is
  # the trap in testing this feature: db.meet/add-meet stamps every new row with
  # clock/sql-today, so a meet created from the Today page comes back dated to
  # today whether or not anything dated it. An implementation that mirrored the
  # task path's day-0 branch and skipped the date at offset 0 would pass a
  # start_date assertion and be wrong. So the request is asserted as well: the
  # date has to have been *set*, not merely to have turned out right.
  Scenario: A meet created from today is dated to today, by being dated and not by default
    Given I am on the app
    When I navigate to the "Today" tab
    And I add a meet "Meet from day zero" via the today add menu
    Then the meet "Meet from day zero" starts on the day at offset 0
    And the start date was set once, to the day at offset 0

  Scenario: A meet created from the fourth day is dated to that day
    Given I am on the app
    When I navigate to the "Today" tab
    And I select the day at offset 3
    And I add a meet "Meet from day three" via the today add menu
    Then the meet "Meet from day three" starts on the day at offset 3
    And the start date was set once, to the day at offset 3

  # The meet being dated has to be the meet that was just created. The refetch
  # the create fires has not landed when the date is set, so at that moment the
  # head of the client's meet list is still the meet seeded below — an
  # implementation that reached for it, as add-task-lined-up-for does, would
  # date that one instead. Asserting the id the request was addressed to is what
  # catches that: both meets are dated either way, so the two dates alone would
  # let a swap through on the day the seeded meet happens to sit at offset 0.
  Scenario: The meet that gets the date is the one just created, not the head of the list
    Given I am on the app
    And a meet "Already in the list" starting at offset 4 exists
    When I click the "Meets" tab
    And I navigate to the "Today" tab
    And I add a meet "Dated from the create response" via the today add menu
    Then the start date was set on the meet "Dated from the create response" itself
    And the meet "Dated from the create response" starts on the day at offset 0
    And the meet "Already in the list" starts on the day at offset 4

  # The task path is unchanged, asymmetry and all: at offset 0 a task is flagged
  # for today, which is a different fact from being dated, and only past offset
  # 0 does it get a date. Flattening the two into one dated call would pass a
  # test that only looked at the day the task turns up in.
  Scenario: A task created from today is flagged for today and dated to nothing
    Given I am on the app
    When I navigate to the "Today" tab
    And I add a task "Task from day zero" via the today add menu
    Then the task "Task from day zero" is flagged for today
    And the task "Task from day zero" is lined up for no day

  Scenario: A task created from the third day is lined up for it and not flagged for today
    Given I am on the app
    When I navigate to the "Today" tab
    And I select the day at offset 2
    And I add a task "Task from day two" via the today add menu
    Then the task "Task from day two" is lined up for the day at offset 2
    And the task "Task from day two" is not flagged for today
