Feature: Journals

  Scenario: User creates a journal and sees it in the list
    Given I am on the app
    And a journal "Work Notes" exists with schedule type "daily"
    When I click the "Resources" tab
    And I click the "Journals" button
    Then I should see "Work Notes" in the journals list
    And I should see "Daily" badge on "Work Notes"

  Scenario: Journals toggle hides importance and sort controls
    Given I am on the app
    And a journal "Daily Log" exists with schedule type "daily"
    When I click the "Resources" tab
    And I click the "Journals" button
    Then the importance filter should not be visible
    And the sort toggle should not be visible

  Scenario: Weekly journal out-of-band entry snaps to the Monday of the picked week
    Given I am on the app
    And a journal "Weekly Snap" exists with schedule type "weekly"
    When I click the "Resources" tab
    And I click the "Journals" button
    And I click "Create Entry" for "Weekly Snap"
    And I pick the date "2026-06-24" in the create-entry modal
    And I click "Create" in the modal
    Then the journal "Weekly Snap" should have an entry on "2026-06-22"
    And the journal "Weekly Snap" should not have an entry on "2026-06-24"

  Scenario: Daily journal out-of-band entry keeps the exact picked date
    Given I am on the app
    And a journal "Daily Exact" exists with schedule type "daily"
    When I click the "Resources" tab
    And I click the "Journals" button
    And I click "Create Entry" for "Daily Exact"
    And I pick the date "2026-06-24" in the create-entry modal
    And I click "Create" in the modal
    Then the journal "Daily Exact" should have an entry on "2026-06-24"

  Scenario: Journals respect scope filter
    Given I am on the app
    And a journal "Private Notes" exists with schedule type "daily" and scope "private"
    When I click the "Resources" tab
    And I click the "Journals" button
    Then I should see "Private Notes" in the journals list
    When I click the "Work" button
    Then I should see "No journals"
