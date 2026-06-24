Feature: Today page relations

  Scenario: Relation-mode toggle is visible in the Today tab navbar
    Given I am on the app
    When I navigate to the "Today" tab
    Then the relation-mode toggle should be visible

  Scenario: Linking two tasks from the Today page shows a relation badge on each
    Given I am on the app
    And a task "Today task alpha" with due date today exists
    And a task "Today task beta" with due date today exists
    When I navigate to the "Today" tab
    And I activate relation mode
    And I click the link button on the today item "Today task alpha"
    And I click the link button on the today item "Today task beta"
    Then today item "Today task alpha" should show a relation badge for "Today task beta"
    And today item "Today task beta" should show a relation badge for "Today task alpha"

  Scenario: Picking a source on Resources and navigating to Today preserves the link mode
    Given I am on the app
    And a task "Today task gamma" with due date today exists
    And a resource "Source resource" with link "https://example.com" exists
    When I navigate to the "Resources" tab
    And I activate relation mode
    And I click the link button on the resource "Source resource"
    And I navigate to the "Today" tab
    And I click the link button on the today item "Today task gamma"
    Then today item "Today task gamma" should show a relation badge for "Source resource"

  Scenario: Task relation badges show a checkbox glyph reflecting the target's done state
    Given I am on the app
    And a task "Checkbox source" with due date today exists
    And a task "Open target" with due date today exists
    And a task "Done target" with due date today exists
    And a resource "Linked resource" with link "https://example.com" exists
    And a relation links task "Checkbox source" to task "Open target"
    And a relation links task "Checkbox source" to task "Done target"
    And a relation links task "Checkbox source" to resource "Linked resource"
    And task "Done target" is done
    When I navigate to the "Today" tab
    Then today item "Checkbox source" relation badge for "Open target" shows "☐"
    And today item "Checkbox source" relation badge for "Open target" is not grayed
    And today item "Checkbox source" relation badge for "Done target" shows "☑"
    And today item "Checkbox source" relation badge for "Done target" is grayed
    And today item "Checkbox source" relation badge for "Linked resource" carries a "res" icon
    And today item "Checkbox source" relation badge for "Linked resource" does not contain "R:"
    And today item "Checkbox source" relation badge for "Done target" appears before the one for "Open target"
    And today item "Checkbox source" relation badges are stacked one per row

  Scenario: Relation badges render above the category tags
    Given I am on the app
    And a task "Layout source" with due date today exists
    And a task "Layout target" with due date today exists
    And a person "Layout person" exists
    And task "Layout source" is categorized as person "Layout person"
    And a relation links task "Layout source" to task "Layout target"
    When I navigate to the "Today" tab
    Then today item "Layout source" should show a relation badge for "Layout target"
    And today item "Layout source" relation badges appear above its category tags

  Scenario: Relation badge title overrides the target's title in the badge
    Given I am on the app
    And a task "Override target" with due date today exists
    And a task "Override source" with due date today exists
    And task "Override target" has relation badge title "OT"
    And a relation links task "Override source" to task "Override target"
    When I navigate to the "Today" tab
    Then today item "Override source" should show a relation badge for "OT"
    And today item "Override source" should not show a relation badge for "Override target"

  Scenario: Relation badges use monochrome type icons instead of letter prefixes
    Given I am on the app
    And a task "Icon source" with due date today exists
    And a task "Icon task target" with due date today exists
    And a resource "Icon resource" with link "https://example.com" exists
    And a meet "Icon meet" dated 3 days from now exists
    And a journal entry "Icon journal" exists
    And a relation links task "Icon source" to task "Icon task target"
    And a relation links task "Icon source" to resource "Icon resource"
    And a relation links task "Icon source" to meet "Icon meet"
    And a relation links task "Icon source" to journal entry "Icon journal"
    When I navigate to the "Today" tab
    Then today item "Icon source" relation badge for "Icon task target" shows "☐"
    And today item "Icon source" relation badge for "Icon resource" carries a "res" icon
    And today item "Icon source" relation badge for "Icon resource" does not contain "R:"
    And today item "Icon source" relation badge for "Icon meet" carries a "met" icon
    And today item "Icon source" relation badge for "Icon meet" does not contain "M:"
    And today item "Icon source" relation badge for "Icon journal" carries a "jen" icon
    And today item "Icon source" relation badge for "Icon journal" does not contain "J:"

  Scenario: Past-meet relation badges are grayed like done-task relations
    Given I am on the app
    And a task "Meet graying source" with due date today exists
    And a meet "Past meet" dated 5 days ago exists
    And a meet "Upcoming meet" dated 5 days from now exists
    And a relation links task "Meet graying source" to meet "Past meet"
    And a relation links task "Meet graying source" to meet "Upcoming meet"
    When I navigate to the "Today" tab
    Then today item "Meet graying source" relation badge for "Past meet" carries a "met" icon
    And today item "Meet graying source" relation badge for "Past meet" is grayed
    And today item "Meet graying source" relation badge for "Upcoming meet" is not grayed
