---
name: write-e2e-tests
description: When you need to write BDD (behaviour driven development) style e2e tests for Tracker
---

Tests are run with `make e2e` or `make e2e-docker` (to verify once they have been verified and developed outside the container).

E2E tests are maintained inside the `e2e` directory.

Configurations are `Dockerfile.e2e` and `playwright.config.ts`. Both will run the tests with an in memory database,
and therefore "start fresh" every time.