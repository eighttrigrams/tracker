Feature: The scope switcher survives a reload

  The switcher decides what every list on every page is allowed to show, so
  losing it on refresh is not a forgotten toggle — it widens every list at once,
  which reads as private items appearing on a work screen. The state is two
  values, the mode and the strict flag, and each is restored from localStorage.

  Every scenario asserts the request as well as the button, because the two can
  disagree in the way that matters: the restore has to land before the boot
  fetches go out, and one that arrives a moment late leaves the switcher drawn
  on the stored scope while the lists underneath it hold everything the wider
  scope returned. Only the query string says which scope was actually asked for.

  Scenario: The chosen scope comes back after a reload
    Given I am on the app
    When I switch scope to "work"
    And I reload the page
    Then the active navbar scope button is "👔"
    And the last "/api/tasks" fetch carried context "work"

  Scenario: Strict mode comes back with it
    Given I am on the app
    And I switch scope to "work"
    When I toggle strict mode
    Then the last "/api/tasks" fetch carried strict "true"
    When I reload the page
    Then the active navbar scope button is "👔"
    And the last "/api/tasks" fetch carried context "work"
    And the last "/api/tasks" fetch carried strict "true"

  # The write has to happen on every change, not only on the ones that narrow
  # the scope. A store that is written when work is picked and left alone
  # afterwards keeps serving "work" to every later reload, and the switcher
  # would refuse to be put back.
  Scenario: Going back to both is remembered too
    Given I am on the app
    And I switch scope to "work"
    When I switch scope to "both"
    And I reload the page
    Then the last "/api/tasks" fetch carried context "both"

  Scenario: Turning strict back off is remembered too
    Given I am on the app
    And I switch scope to "work"
    And I toggle strict mode
    When I toggle strict mode
    Then the last "/api/tasks" fetch carried strict "false"
    When I reload the page
    Then the last "/api/tasks" fetch carried context "work"
    And the last "/api/tasks" fetch carried strict "false"

  # A browser that has never touched the switcher has nothing stored, and the
  # defaults still have to apply — this is the scenario that fails if the
  # restore treats an absent value as a value.
  Scenario: A browser with nothing stored starts on both, not strict
    Given I am on the app
    Then the last "/api/tasks" fetch carried context "both"
    And the last "/api/tasks" fetch carried strict "false"
