.PHONY: start stop start-prod build deploy clean fly-backup fly-backup-replay

start:
	./scripts/start.sh

stop:
	./scripts/stop.sh

start-prod:
	./scripts/start.sh prod

build:
	npm install
	npx shadow-cljs release app
	clj -T:build uber

deploy:
	fly deploy

clean:
	rm -rf target node_modules .shadow-cljs resources/public/js

fly-backup:
	fly ssh console -C "tar -czf - /app/data" > volume-backup.$$(date +%Y-%m-%d.%H-%M).tar.gz

fly-backup-replay:
	@if [ -d data ]; then echo "Error: data/ directory already exists. Remove it first." && exit 1; fi
	tar -xzf $$(ls -t volume-backup.*.tar.gz | head -1) --strip-components=1
