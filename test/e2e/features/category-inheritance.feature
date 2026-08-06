Feature: A new Item inherits the Category filters it was added under

  # No Group is named in this file or in its steps. constants/category-groups
  # puts one .filter-section into every sidebar, so the steps read the Groups off
  # the page and seed, filter and assert over whatever they find — a seventh
  # Group is covered here the day it is added, without anybody remembering to
  # come back. Listing the six by hand is exactly the mistake under test: seven
  # add sites spelled out the four original Groups, and Workstreams and Assets
  # fell on the floor at all seven.

  Scenario: A task added under a filter in every Group carries a category from every Group
    Given I am on the app
    And a category exists in every Group
    And I reload the page
    When I click the "Tasks" tab
    And I filter by the seeded category in every Group
    And I add "Inherit all on tasks" on the "tasks" page
    Then the "tasks" item "Inherit all on tasks" carries a category from every Group
    And one categorize request was sent per Group

  Scenario: An issue added under a filter in every Group carries a category from every Group
    Given I am on the app
    And a category exists in every Group
    And I reload the page
    When I click the "Issues" tab
    And I filter by the seeded category in every Group
    And I add "Inherit all on issues" on the "issues" page
    Then the "issues" item "Inherit all on issues" carries a category from every Group
    And one categorize request was sent per Group

  Scenario: A meet added under a filter in every Group carries a category from every Group
    Given I am on the app
    And a category exists in every Group
    And I reload the page
    When I click the "Meets" tab
    And I filter by the seeded category in every Group
    And I add "Inherit all on meets" on the "meets" page
    Then the "meets" item "Inherit all on meets" carries a category from every Group
    And one categorize request was sent per Group

  Scenario: A meeting series added under a filter in every Group carries a category from every Group
    Given I am on the app
    And a category exists in every Group
    And I reload the page
    When I click the "Meets" tab
    And I click the "Series" button
    And I filter by the seeded category in every Group
    And I add "Inherit all on series" on the "meeting-series" page
    Then the "meeting-series" item "Inherit all on series" carries a category from every Group
    And one categorize request was sent per Group

  Scenario: A recurring task added under a filter in every Group carries a category from every Group
    Given I am on the app
    And a category exists in every Group
    And I reload the page
    When I click the "Tasks" tab
    And I click the "Recurring" button
    And I filter by the seeded category in every Group
    And I add "Inherit all on recurring" on the "recurring-tasks" page
    Then the "recurring-tasks" item "Inherit all on recurring" carries a category from every Group
    And one categorize request was sent per Group

  Scenario: A resource added under a filter in every Group carries a category from every Group
    Given I am on the app
    And a category exists in every Group
    And I reload the page
    When I click the "Resources" tab
    And I filter by the seeded category in every Group
    And I add "Inherit all on resources" on the "resources" page
    Then the "resources" item "Inherit all on resources" carries a category from every Group
    And one categorize request was sent per Group

  # The eighth add path, and the one the work order's own count of seven missed:
  # the Journal. Its add form is the Resources page in Journals mode, under the
  # same sidebar as the other seven, and its list is filtered by the same six
  # filters — so a Journal that does not carry them drops out of the list it was
  # added from. The extra click is the schedule-type modal this form opens; no
  # other add form asks anything before creating.
  Scenario: A journal added under a filter in every Group carries a category from every Group
    Given I am on the app
    And a category exists in every Group
    And I reload the page
    When I click the "Resources" tab
    And I click the "Journals" button
    And I filter by the seeded category in every Group
    And I add "Inherit all on journals" on the "journals" page
    Then the "journals" item "Inherit all on journals" carries a category from every Group
    And one categorize request was sent per Group

  # The Issue page creating a Task that belongs to the Issue. It posts to
  # /api/tasks, not /api/issues, which is the one call site whose collection
  # segment is not its own page's.
  Scenario: A task created from an issue carries a category from every Group
    Given I am on the app
    And a category exists in every Group
    And an issue "Host issue" exists
    And the issue "Host issue" carries the seeded category of every Group
    And I reload the page
    When I click the "Issues" tab
    And I filter by the seeded category in every Group
    And I click the create-task button on issue "Host issue"
    And I enter "Inherit all from issue" as the task title
    And I confirm the create-task modal
    Then the "tasks" item "Inherit all from issue" carries a category from every Group

  # The nastiest symptom of the old bug: with two Groups selected the Item came
  # back carrying one of them, which reads as inheritance working. A test that
  # asserted "some category was applied" passed while the Workstream was lost.
  Scenario: A workstream and a project selected together both land on the new task
    Given I am on the app
    And a category exists in every Group
    And I reload the page
    When I click the "Tasks" tab
    And I filter by the seeded category in the "workstreams" Group
    And I filter by the seeded category in the "projects" Group
    And I add "Workstream and project" on the "tasks" page
    Then the "tasks" item "Workstream and project" carries the seeded category of the "workstreams" Group
    And the "tasks" item "Workstream and project" carries the seeded category of the "projects" Group

  Scenario: A workstream on its own lands on the new task
    Given I am on the app
    And a category exists in every Group
    And I reload the page
    When I click the "Tasks" tab
    And I filter by the seeded category in the "workstreams" Group
    And I add "Workstream only" on the "tasks" page
    Then the "tasks" item "Workstream only" carries the seeded category of the "workstreams" Group
    And exactly 1 categorize request was sent

  Scenario: An asset on its own lands on the new task
    Given I am on the app
    And a category exists in every Group
    And I reload the page
    When I click the "Tasks" tab
    And I filter by the seeded category in the "assets" Group
    And I add "Asset only" on the "tasks" page
    Then the "tasks" item "Asset only" carries the seeded category of the "assets" Group
    And exactly 1 categorize request was sent

  # add-task's other branch: no filter is up, so the plain POST runs and nothing
  # is posted afterwards — not even an empty categorize per Group.
  Scenario: A task added with no filter selected posts no categorize at all
    Given I am on the app
    And a category exists in every Group
    And I reload the page
    When I click the "Tasks" tab
    And I add "No filters here" on the "tasks" page
    Then the "tasks" item "No filters here" carries no categories
    And exactly 0 categorize requests were sent
