---
name: write-e2e-tests
description: When you need to write BDD (behaviour driven development) style e2e tests for Tracker
---

There are two ways to run the tests
- directly on the host system
- inside a docker container

To be meaningful, when we run the tests directly on the host system, we start the app
with the following settings:

```clojure
{:db {:type 
      ;; such that we always start with no data
      :sqlite-memory}
 :dangerously-skip-logins? true
 ;; that here is negotiable:
 :shadow? true}
```

Tests are run with `make e2e` or `make e2e-docker` (to verify once they have been verified and developed outside the container).

E2E tests are maintained inside the `e2e` directory.

Configurations are `Dockerfile.e2e` and `playwright.config.ts`.