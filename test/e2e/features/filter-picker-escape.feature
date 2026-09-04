Feature: Escape in a Group's picker is the app's key, not the browser's

  Option+Escape inside a Group's picker clears just that Group. To keep core's
  Option+Escape from clearing all six on the way past, the picker's handler
  stops the event — and stopping it also threw away the only preventDefault on
  that path, so the keystroke went out fully handled and still live and the OS
  was free to compose a character from it into the page search box the handler
  focuses next.

  Scenario: Option+Shift+Escape in a picker is consumed rather than left live
    Given I am on the app
    And test data with categorized tasks exists
    And I reload the page
    And I click the "Tasks" tab
    When I filter by place "Lagos"
    And I press Option+Shift+Escape in the "places" picker
    Then the keystroke should have been consumed by the app
    And nothing should be selected in the "places" filter group
    And the page search box should be empty
