Feature: Category badge clickability

  # What the badges do with a click is a filter per type, so that is what this
  # asserts; the pointer cursor is checked alongside it, never on its own. The
  # reload is what puts the seeded categories into the sidebar's filter lists,
  # which load once at app start — without a selection to wait for, a cursor
  # read straight after the click is answered by the pre-click frame and cannot
  # fail. Every cursor assertion below stands behind one of those selections.
  Scenario: Clicking badges activates filters per type
    Given I am on the app
    And test data with categorized tasks exists
    And I reload the page
    When I click the "Tasks" tab
    Then the "Lagos" badge on task "Fix plumbing" should be clickable
    And the "Renovations" badge on task "Fix plumbing" should be clickable
    And the "Bordeira" badge on task "Paint walls" should be clickable
    When I click the "Lagos" badge on task "Fix plumbing"
    Then the "places" filter should show "Lagos" as selected
    And nothing should be selected in the "projects" filter group
    And the "Renovations" badge on task "Fix plumbing" should be clickable
    And the "Bordeira" badge on task "Paint walls" should be clickable
    When I click the "Renovations" badge on task "Fix plumbing"
    Then the "projects" filter should show "Renovations" as selected
    And the "places" filter should show "Lagos" as selected
