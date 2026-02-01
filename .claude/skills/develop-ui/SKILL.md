---
name: develop-ui
description: To develop the user interface of the application, you need to know how to start, stop, restart the app and where to find it.
---

Start with `make start`

Stop with `make stop`. Unless you are specifically asked to. Don't use this on your own to 'just' shut down the app. You may only use this if you want to restart the app, then in conjuction with `make start`. Importantly, use `nohup make start $` and `nohup make start $` or use other ways to run those in the BACKGROUND.

The running app is assessible at localhost:3027.

To gain inside of whats happening while the application is running (especially when its sent to the background, as we always want to do!), tail the logs at `logs/tracker.log`.
A complete log of a testrun is written to `logs/tracker.tests.log`.
