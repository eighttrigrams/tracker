Feature: Item type icons and ordering on the Reports page

  Background:
    Given I am on the app
    And a report day with a task, a meet, and a journal entry exists
    And I reload the page

  Scenario: The Reports page shows no per-type sub-headings
    When I click the "Reports" tab
    Then I should see "Buy paint" in the reports
    And I should not see any report type sub-headings

  Scenario: Meet titles carry the calendar glyph and journal titles the notepad glyph
    When I click the "Reports" tab
    And I increase the reports scope
    Then the report meet title carries the calendar glyph
    And the report journal title carries the notepad glyph
    And the report task title carries the checkbox glyph

  Scenario: Items under a day are ordered journals, then tasks, then meets
    When I click the "Reports" tab
    And I increase the reports scope
    Then the report day items appear in order journals, then tasks, then meets

  Scenario: The journal notepad glyph is hidden in the journals-only view
    When I click the "Reports" tab
    Then the report journal title carries the notepad glyph
    When I select the "Journals" reports filter
    And I switch the journals view to cards
    Then the report journal title carries no notepad glyph
