Feature: Right-clicking an item card opens its footer menu

  Scenario: A collapsed card offers the footer menu, and an entry from it fires
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Context menu task"
    When I right-click the card "Context menu task"
    Then the card menu offers "Mark task done, Set Reminder, Delete"
    When I click the card menu entry "Delete"
    Then I see the delete confirmation

  # An open card offers the menu too, but off its title only. Over the
  # description, the links in it and the category selectors the browser's own
  # menu — select, copy, copy link address, open in a new tab — is worth more
  # than a second route to a footer that is already on screen.
  Scenario: An open card offers the menu on its title and leaves its body alone
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Context menu task"
    And I expand the task card "Context menu task"
    Then a right-click on the title of "Context menu task" is taken over by the app
    And the card "Context menu task" is still open
    And a right-click on the body of "Context menu task" is left to the browser
    When I collapse the task card "Context menu task"
    Then a right-click on the card "Context menu task" is taken over by the app

  # Plain Escape also collapses an open card, and the menu is dismissed by
  # Escape as well — so with the menu now reachable *on* an open card, one press
  # would have done both. It takes one thing at a time, nearest first.
  Scenario: Escape closes the menu without collapsing the card under it
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Layered escape task"
    And I expand the task card "Layered escape task"
    When I right-click the title of "Layered escape task"
    Then the card menu offers "Mark task done, Set Reminder, Delete"
    When I press Escape
    Then no card menu is open
    And the card "Layered escape task" is still open
    When I press Escape
    Then the card "Layered escape task" is collapsed

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

  # Two cards: the picker sits in a footer, so it is on the open one, and the
  # menu is opened on the other — the cross-card case, where the popup being
  # closed belongs to a card that is not re-rendering for the menu at all.
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

  # And the same-card case, which the title handle made reachable: the picker and
  # the menu now belong to one card, so `close-card-popups!` is closing a popup
  # on the very card that is opening one.
  Scenario: Opening the menu on an open card closes its own send-to-day picker
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Own popup task"
    And I expand the task card "Own popup task"
    And I open the send-to-day picker on task "Own popup task"
    Then the send-to-day picker on task "Own popup task" is open
    When I right-click the title of "Own popup task"
    Then the card menu offers "Mark task done, Set Reminder, Delete"
    And the send-to-day picker on task "Own popup task" is closed

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

  # One card now, where this used to need two: the toggle groups can only be read
  # off an open footer, and an open card's title takes the right-click, so the
  # footer being compared is the very one the menu was built from.
  Scenario: The footer's toggle groups stay out of the menu
    Given I am on the app
    And I click the "Tasks" tab
    And I add a task called "Toggle groups task"
    And I expand the task card "Toggle groups task"
    Then the footer of "Toggle groups task" shows the scope, importance and urgency toggle groups
    When I right-click the title of "Toggle groups task"
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
