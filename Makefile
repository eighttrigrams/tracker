.PHONY: start stop start-prod build test e2e e2e-docker lint deploy clean backup backup-replay

start:
	@if [ -f .env ]; then set -a && . ./.env && set +a; fi && ./scripts/start.sh

stop:
	./scripts/stop.sh

start-prod:
	./scripts/start.sh prod

build:
	npm install
	npx shadow-cljs release app
	clj -T:build uber

test:
	clj -X:test

e2e:
	./scripts/stop.sh && npx shadow-cljs release app && npx bddgen && npx playwright test

e2e-docker:
	./scripts/run-e2e-docker.sh

lint:
	clj-kondo --lint src/clj src/cljc test/clj

deploy:
	fly deploy

clean:
	rm -rf target node_modules .shadow-cljs resources/public/js

backup:
	@mkdir -p .backups
	fly ssh console -C "tar -czf - /app/data" > .backups/volume-backup.$$(date +%Y-%m-%d.%H-%M).tar.gz

backup-replay:
	@if [ -d data ]; then echo "Error: data/ directory already exists. Remove it first." && exit 1; fi
	tar -xzf $$(ls -t .backups/volume-backup.*.tar.gz | head -1) --strip-components=1
