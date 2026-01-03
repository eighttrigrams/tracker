.PHONY: start stop start-prod build deploy clean

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
