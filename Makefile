.PHONY: start stop build test e2e e2e-docker lint clean backup backup-replay

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

# Usage:
#   make e2e                          full run
#   make e2e T="scenario substring"   filter via playwright -g
#   make e2e NO_BUILD=1               skip `shadow-cljs release` (reuses
#                                     the previously built main.js — fine
#                                     when no cljs changed since last run)
e2e:
	./scripts/stop.sh check && \
	$(if $(NO_BUILD),true,npx shadow-cljs release app) && \
	npx bddgen -c test/playwright.config.ts && \
	npx playwright test -c test/playwright.config.ts $(if $(T),-g "$(T)")

e2e-docker:
	./scripts/run-e2e-docker.sh

lint:
	clj-kondo --lint src/clj src/cljc test/unit

clean:
	rm -rf target node_modules .shadow-cljs resources/public/js
