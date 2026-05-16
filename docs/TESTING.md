# Testing

## Test layout

```
test/
  unit/                 # clojure.test unit tests
  integration/          # (reserved — same :test alias would pick these up)
  e2e/
    features/*.feature  # Gherkin scenarios
    steps/*.ts          # step definitions
  playwright.config.ts  # webServer + chromium config for the e2e run
```

The `:test` alias in `deps.edn` registers `test/unit` and `test/integration` as the test-runner roots, so a top-level `clj -X:test` discovers both.

## Unit Tests

```bash
make test
```

Runs the Clojure test suite via `clj -X:test`.

To run a single test namespace:

```bash
make test NS=et.tr.scheduling-test
```

## E2E Tests

E2E tests use [Playwright](https://playwright.dev/) with [playwright-bdd](https://github.com/vitalets/playwright-bdd) for BDD-style Gherkin syntax. The server is launched against a fresh in-memory sqlite (see `config.edn` / `config.edn.template`: `:db {:type :sqlite-memory}`), with `:dangerously-skip-logins? true`, so each `make e2e` starts from a clean state.

### Prerequisites

```bash
npm install
npx playwright install chromium
```

### Running

```bash
make e2e                          # full run
make e2e T="scenario substring"   # only matching scenarios (playwright -g)
make e2e NO_BUILD=1               # skip `shadow-cljs release` (reuse last
                                  # main.js — fine when no cljs changed)
make e2e NO_BUILD=1 T="resources" # combine both
```

`make e2e` does, in order:

1. `./scripts/stop.sh check` — refuses to run if anything is already bound to `PORT` or `SHADOW_PORT` (dev server, another e2e run, the docker `make box` port-forwards). Resolve the conflict manually (`make stop`, shut down `make box`, etc.) and re-run.
2. `npx shadow-cljs release app` — production cljs build, no hot-reload runtime / no websockets in the served `main.js`. **Skipped** when `NO_BUILD=1`.
3. `npx bddgen` — regenerates the playwright spec files from the `.feature` files into `test/.features-gen/`.
4. `npx playwright test` — boots the clojure server (via `webServer` in `test/playwright.config.ts`, with `cwd` set to the project root so `config.edn` is read from there) and runs the scenarios. The webServer command is plain `DEV=true clojure -X:run :e2e true`; the port comes from `config.edn`'s aero `#env PORT` literal.

### Port unification

E2E and the dev server share the same `PORT` (3110 by default, overridable via `.envrc`). We never run them concurrently — `stop.sh check` enforces that — so there's no need for a separate `E2E_PORT`. `playwright.config.ts` polls `127.0.0.1` (not `localhost`) to sidestep the macOS IPv6/IPv4 asymmetry: Jetty binds to `127.0.0.1`, and `localhost` resolves to `::1` first on macOS, which Jetty isn't listening on.

### Writing Tests

- **`test/e2e/features/*.feature`** — Gherkin feature files (Given/When/Then)
- **`test/e2e/steps/*.ts`** — Step definitions that map Gherkin steps to Playwright actions

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

Step definitions are reusable across features. Add new steps in `test/e2e/steps/` as needed.

### Configuration

Playwright config lives in `test/playwright.config.ts`. Tests run headless (`CI=1` is set in `Dockerfile.e2e`; on the host the config defaults to headless too). The `RATE_LIMIT_MAX_REQUESTS=99999` env is injected for the test server so the dev rate limit doesn't trip during burst-y scenarios.

## Dockerized E2E Tests

Runs E2E tests in an isolated Docker container — no local dependencies needed beyond Docker itself. Useful for cronjobs or CI.

```bash
make e2e-docker
```

This builds `Dockerfile.e2e` (bookworm + chromium + playwright + the app), runs `shadow-cljs release` at image build time, and on container start runs `npx bddgen -c test/playwright.config.ts && npx playwright test -c test/playwright.config.ts`. Exits with the test result code (0 = pass).

`config.edn` is gitignored (user-local), so `Dockerfile.e2e` copies `config.edn.template` in as `/app/config.edn`. The template carries the same e2e-friendly defaults (sqlite-memory, skip-logins, port 3110) the host run uses.

On completion, a test report message is sent to the running Tracker instance (requires `.credentials` file).
