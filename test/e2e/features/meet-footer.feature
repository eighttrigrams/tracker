Feature: Unified meet footer (button + dropdown)

  Scenario: An archivable today meet shows a "Meet over" anchor plus dropdown, not three flat buttons
    Given I am on the app
    And a meet "Footer archivable" with start date today exists
    When I navigate to the "Today" tab
    And I expand the today meet "Footer archivable"
    Then the meet footer anchor button shows "Meet over"
    And the meet footer anchor button is blue
    And the meet footer has no flat archive button
    When I open the meet footer dropdown on "Footer archivable"
    Then the meet footer dropdown items in order are "Set maybe, Archive, Delete"
    And the meet footer dropdown "Archive" item is non-destructive
    And the meet footer dropdown "Set maybe" item is non-destructive
    And the meet footer dropdown delete item is red

  Scenario: Archive from the meet footer dropdown removes the meet
    Given I am on the app
    And a meet "Footer to archive" with start date today exists
    When I navigate to the "Today" tab
    And I expand the today meet "Footer to archive"
    And I open the meet footer dropdown on "Footer to archive"
    And I click the footer dropdown item "Archive"
    Then the today section should no longer show "Footer to archive"

  Scenario: Delete from the meet footer dropdown opens the delete confirmation
    Given I am on the app
    And a meet "Footer to delete" with start date today exists
    When I navigate to the "Today" tab
    And I expand the today meet "Footer to delete"
    And I open the meet footer dropdown on "Footer to delete"
    And I click the footer dropdown item "Delete"
    Then I see the delete confirmation

  Scenario: A future meet in the strip uses a "Meet over" anchor with maybe and Delete in the dropdown
    Given I am on the app
    And a meet "Footer future" with start date tomorrow exists
    When I navigate to the "Today" tab
    And I click the second day button
    And I expand the today meet "Footer future"
    Then the meet footer anchor button shows "Meet over"
    And the meet footer anchor button is blue
    And the meet footer has no flat archive button
    When I open the meet footer dropdown on "Footer future"
    Then the meet footer dropdown shows "maybe"
    And the meet footer dropdown shows "Delete"

  Scenario: An over today meet anchors on Archive with a non-destructive "Meet not over" in the dropdown
    Given I am on the app
    And a meet "Footer over" with start date today exists
    When I navigate to the "Today" tab
    And I expand the today meet "Footer over"
    And I click the meet footer anchor button
    Then the meet footer anchor button shows "Archive"
    And the meet footer anchor button is blue
    When I open the meet footer dropdown on "Footer over"
    Then the meet footer dropdown items in order are "Meet not over, Delete"
    And the meet footer dropdown "Meet not over" item is non-destructive
    And the meet footer dropdown delete item is red

  Scenario: A non-archivable meet in the upcoming section is a plain Delete button
    Given I am on the app
    And a meet "Footer upcoming" with start date 7 days from now exists
    When I navigate to the "Today" tab
    And I click the "Upcoming" view switcher button
    And I expand the upcoming meet "Footer upcoming"
    Then the meet footer is a standalone delete button
