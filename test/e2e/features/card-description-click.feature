Feature: A held modifier hands the description click back to the browser

  # An open card's description is one big click target: clicking it opens the
  # edit modal. That is the app's gesture, and it holds for a plain click only —
  # with a modifier down the click belongs to the browser, which is how a link in
  # the rendered markdown is opened in a new tab (or a window, or saved). The
  # modal landing on top of that would bury the very thing the gesture asked for.

  Scenario: A plain click still opens the editor
    Given I am on the app
    And a resource "Modifier notes" with description "Notes with a [link](/) in them" exists
    When I reload the page
    And I click the "Resources" tab
    And I expand resource "Modifier notes"
    And I click the description of resource "Modifier notes"
    Then the description editor holds "Notes with a [link](/) in them"

  # Every modifier, not the one platform binding we happened to have in mind: the
  # rule is "any modifier", so a binding we never thought of is not swallowed
  # either. Each case ends with the plain click that does open the modal, so a
  # description that had stopped responding altogether cannot pass as a pass —
  # and the selection is dropped first because a shift-click leaves one behind,
  # which the description's *other* rule (no editor while its text is selected)
  # would otherwise be the reason the confirming click found nothing.
  Scenario Outline: A <modifier>-click on the description opens no editor
    Given I am on the app
    And a resource "Modifier notes" with description "Notes with a [link](/) in them" exists
    When I reload the page
    And I click the "Resources" tab
    And I expand resource "Modifier notes"
    And I <modifier>-click the description of resource "Modifier notes"
    Then no edit modal opens
    When I clear the text selection
    And I click the description of resource "Modifier notes"
    Then the description editor holds "Notes with a [link](/) in them"

    Examples:
      | modifier |
      | Meta     |
      | Control  |
      | Shift    |
      | Alt      |

  # The gesture the rule exists for, end to end.
  Scenario: Cmd-clicking a link in the description opens it in a new tab
    Given I am on the app
    And a resource "Modifier notes" with description "Notes with a [link](/) in them" exists
    When I reload the page
    And I click the "Resources" tab
    And I expand resource "Modifier notes"
    Then cmd-clicking "link" in the description opens a new tab
    And no edit modal opens
