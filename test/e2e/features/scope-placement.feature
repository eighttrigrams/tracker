Feature: Inverted placement of the scope switcher

  Scenario: By default private sits on the left in the navbar and on a card
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Scope placement task"
    When I expand the task card "Scope placement task"
    Then the navbar scope switcher reads "🏠, 👔"
    And the scope switcher on task "Scope placement task" reads "private, both, work"

  Scenario: Inverted placement moves private to the right in both switchers and survives a reload
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Scope placement task"
    When I turn on inverted scope placement
    And I click the "Tasks" tab
    And I expand the task card "Scope placement task"
    Then the navbar scope switcher reads "👔, 🏠"
    And the scope switcher on task "Scope placement task" reads "work, both, private"
    When I reload the page
    And I click the "Tasks" tab
    And I expand the task card "Scope placement task"
    Then the navbar scope switcher reads "👔, 🏠"
    And the scope switcher on task "Scope placement task" reads "work, both, private"
    When I turn off inverted scope placement
    And I click the "Tasks" tab
    And I expand the task card "Scope placement task"
    Then the navbar scope switcher reads "🏠, 👔"
    And the scope switcher on task "Scope placement task" reads "private, both, work"

  Scenario: The switcher still sets the scope it points at when inverted
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Inverted scope write"
    When I turn on inverted scope placement
    And I click the "Tasks" tab
    And I expand the task card "Inverted scope write"
    And I click "private" on the scope switcher of task "Inverted scope write"
    Then the scope of task "Inverted scope write" is "private"
