Feature: Reports task action dropdown

  The report task card's Delete/▼ action menu is now declared as a footer-button
  spec, so it inherits the shared close-on-unmount guarantee: a collapsed and
  re-expanded card must never show a stale-open dropdown.

  Background:
    Given I am on the app
    And done report tasks "Paint fence" and "Fix sink" exist
    And I reload the page

  Scenario: The report task dropdown does not reopen stale after collapse and re-expand
    When I click the "Reports" tab
    And I expand the report item "Paint fence"
    And I open the action dropdown on report item "Paint fence"
    Then the action dropdown on report item "Paint fence" is open
    When I expand the report item "Fix sink"
    And I expand the report item "Paint fence"
    Then the action dropdown on report item "Paint fence" is closed
