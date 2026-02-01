---
name: tracker-codebase-codestyle
description: Rules to follow and guidelines for working on this codebase.
---

- We use HoneySQL instead of bashing SQL strings together
- Try to keep the frontend "as dumb as possible", i.e. when asked to implement filtering of items, make sure that is done "behind the API" i.e. in the backend (instead of delivering *all* items to the frontend and filtering there)
