# Testing

## Unit Tests

```bash
make test
```

Runs the Clojure test suite via `clj -X:test`.

## E2E Tests

E2E tests use [Playwright](https://playwright.dev/) with [playwright-bdd](https://github.com/vitalets/playwright-bdd) for BDD-style Gherkin syntax.

### Prerequisites

```bash
npm install
npx playwright install chromium
```

### Running

```bash
make e2e
```

This generates Playwright test files from the Gherkin features (`npx bddgen`) and then runs them. The app is started automatically via Playwright's `webServer` config and shut down after the tests complete. If the app is already running on port 3027, it reuses the existing server.

### Writing Tests

Tests are structured as:

- **`e2e/features/*.feature`** — Gherkin feature files (Given/When/Then)
- **`e2e/steps/*.ts`** — Step definitions that map Gherkin steps to Playwright actions

Example feature:

```gherkin
Feature: Tasks

  Scenario: User can add a task
    Given I am on the app
    When I click the "Tasks" tab
    And I type "My new test task" in the search field
    And I click the add button
    Then I should see "My new test task" in the task list
```

Step definitions are reusable across features. Add new steps in `e2e/steps/` as needed.

### Configuration

Playwright config lives in `playwright.config.ts`. Tests run headed locally by default; set `CI=1` for headless mode.

## Dockerized E2E Tests

Runs E2E tests in an isolated Docker container — no local dependencies needed beyond Docker itself. Useful for cronjobs or CI.

```bash
make e2e-docker
```

This builds a Docker image with the app and Playwright/Chromium, starts the server inside the container, runs the tests headless, and exits with the test result code (0 = pass).

On completion, a test report message is sent to the running Tracker instance (requires `.credentials` file).

### Cronjob Setup

`scripts/run-e2e-cronjob.sh` pulls the latest main and runs the Docker E2E tests. It expects a repo directory as argument:

```bash
./scripts/run-e2e-cronjob.sh /path/to/tracker-copy
```

To set up a cronjob (e.g. every 5 minutes):

```bash
crontab -e
```

```
*/5 * * * * /path/to/tracker-copy/scripts/run-e2e-cronjob.sh /path/to/tracker-copy >> /path/to/tracker-copy/logs/cronjob.log 2>&1
```

Use a separate clone of the repo so the cronjob doesn't interfere with local development.
