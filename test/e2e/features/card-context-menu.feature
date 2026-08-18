Feature: Right-clicking a collapsed item card opens its footer menu

  Scenario: A collapsed card offers the footer menu, and an entry from it fires
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Context menu task"
    When I right-click the card "Context menu task"
    Then the card menu offers "Mark task done, Set Reminder, Delete"
    When I click the card menu entry "Delete"
    Then I see the delete confirmation

  # The menu stands in for the footer, and an expanded card shows that footer
  # already — so over its description and the links in it the browser's own menu
  # is worth more than a second route to buttons a few pixels below, and the
  # card hands the right-click back for as long as it is open.
  Scenario: An expanded card leaves the right-click to the browser
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Context menu task"
    And I expand the task card "Context menu task"
    Then a right-click on the card "Context menu task" is left to the browser
    When I collapse the task card "Context menu task"
    Then a right-click on the card "Context menu task" is taken over by the app

  Scenario: Escape and a click elsewhere close the menu
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Context menu task"
    When I right-click the card "Context menu task"
    Then the card menu offers "Mark task done, Set Reminder, Delete"
    When I press Escape
    Then no card menu is open
    When I right-click the card "Context menu task"
    Then the card menu offers "Mark task done, Set Reminder, Delete"
    When I click outside the card menu
    Then no card menu is open

  # The menu is a portal, so its DOM node lives in <body> rather than in the
  # card; it stays a child of the card in the React tree, and that is what has
  # to take it down. The tab has to be left by keyboard: clicking one is an
  # outside mousedown, which dismisses the menu before the card is ever
  # destroyed, so a clicked tab switch tests the dismiss and not the unmount.
  # Coming back matters as much as leaving — the open menu is held in one global
  # atom, and were it keyed by item id rather than by a per-mount token, the
  # card would find its own stale entry again on remount.
  Scenario: The menu cannot outlive the card it belongs to
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Context menu task"
    When I right-click the card "Context menu task"
    Then the card menu offers "Mark task done, Set Reminder, Delete"
    When I press the keyboard shortcut for the "Resources" tab
    Then no card menu is open
    When I click the "Tasks" tab
    Then I should see "Context menu task" in the task list
    And no card menu is open

  # Two cards, because the picker sits in a footer and only an expanded card has
  # one, while only a collapsed card takes the right-click: the popup to be
  # closed is therefore always on some *other* card than the menu being opened.
  Scenario: Opening the menu closes another card's send-to-day picker
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Two popups task"
    And I add a task called "Menu opening task"
    And I expand the task card "Two popups task"
    And I open the send-to-day picker on task "Two popups task"
    Then the send-to-day picker on task "Two popups task" is open
    When I right-click the card "Menu opening task"
    Then the card menu offers "Mark task done, Set Reminder, Delete"
    And the send-to-day picker on task "Two popups task" is closed

  Scenario: Outside a card the browser keeps its own menu
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Context menu task"
    Then a right-click on the card "Context menu task" is taken over by the app
    And a right-click on the page background is left to the browser

  Scenario: An inbox card's menu carries the action its footer holds on the left
    Given I am on the app
    And an inbox message "Inbox menu message" exists
    And I click the "Inbox" tab
    When I right-click the card "Inbox menu message"
    Then the card menu offers "Save for Later, Convert to task, Delete"
    When I click the card menu entry "Save for Later"
    Then the message "Inbox menu message" is saved for later

  Scenario: A YouTube inbox item offers the convert its footer offers instead
    Given I am on the app
    And a YouTube inbox message "New clip https://www.youtube.com/watch?v=abc123" exists
    And I click the "Inbox" tab
    When I right-click the card "New clip"
    Then the card menu offers "Convert to resource, Delete"

  # Again two cards: the toggle groups can only be read off an expanded footer,
  # and the menu can only be opened on a collapsed card. Task footers are all
  # built from the same spec, so the options on the open one are the ones the
  # collapsed one's menu must not carry either.
  Scenario: The footer's toggle groups stay out of the menu
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Toggle groups task"
    And I add a task called "Menu opening task"
    And I expand the task card "Toggle groups task"
    Then the footer of "Toggle groups task" shows the scope, importance and urgency toggle groups
    When I right-click the card "Menu opening task"
    Then the card menu offers "Mark task done, Set Reminder, Delete"
    And no card menu entry is one of those toggle options

  Scenario: A resource with a link offers to copy it, one without does not
    Given I am on the app
    And a resource "Linked resource" with link "https://example.com/linked" exists
    And a resource "Sheetish resource" without a link exists
    And I click the "Resources" tab
    When I right-click the card "Sheetish resource"
    Then the card menu offers "Delete"
    When I right-click the card "Linked resource"
    Then the card menu offers "Delete, Copy link"
    When I click the card menu entry "Copy link"
    Then the copy signal flashes
    And the clipboard holds "https://example.com/linked"
