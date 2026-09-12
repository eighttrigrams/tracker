.PHONY: start stop build test test-cljs test-all e2e e2e-docker lint clean backup backup-replay

start:
	@if [ -f .env ]; then set -a && . ./.env && set +a; fi && ./scripts/start.sh

stop:
	./scripts/stop.sh

build:
	npm install
	npx shadow-cljs release app
	clj -T:build uber

test:
ifdef NS
	DEV=true clojure -M:test -n $(NS)
else
	DEV=true clj -X:test
endif

# The ClojureScript suite, which is the seal's half of the drift control.
#
# `make test` alone says nothing about the envelope: somebody could edit
# src/cljs/et/tr/ui/seal.cljs, run it, and ship a browser that no longer agrees
# with plurama-cli's tracker_seal.clj — two implementations of one envelope, and
# the divergence found six months later in a body nobody can open. Both suites
# read tracker/test/fixtures/seal-vectors.edn, and `test-all` is the one to run
# before believing a seal change.
test-cljs:
	npx shadow-cljs compile test
	node target/node-tests.js

test-all: test test-cljs

# Usage:
#   make e2e                          full run
#   make e2e T="scenario substring"   filter via playwright -g
#   make e2e NO_BUILD=1               skip `shadow-cljs release` (reuses
#                                     the previously built main.js — fine
#                                     when no cljs changed since last run)
#
# TRACKER_FAKE_TODAY pins "today" for the whole run (backend clock, browser
# clock, and seeds) to a fixed mid-week Wednesday so date-sensitive specs are
# weekday-independent — see et.tr.clock and test/e2e/steps/{helpers,_hooks}.ts.
# It is set on the playwright process, which propagates it to the spawned
# webServer (which inherits the parent env).
e2e: export TRACKER_FAKE_TODAY ?= 2026-07-15
e2e:
	./scripts/stop.sh check && \
	$(if $(NO_BUILD),true,npx shadow-cljs release app) && \
	npx bddgen -c test/playwright.config.ts && \
	npx playwright test -c test/playwright.config.ts $(if $(T),-g "$(T)")

e2e-docker:
	./scripts/run-e2e-docker.sh

lint:
	clj-kondo --lint src/clj src/cljc src/cljs test/unit

clean:
	rm -rf target node_modules .shadow-cljs resources/public/js
