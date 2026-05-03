FROM clojure:temurin-21-tools-deps-alpine AS builder

WORKDIR /opt

RUN apk add --no-cache nodejs npm

COPY deps.edn build.clj package.json shadow-cljs.edn ./
COPY src ./src
COPY resources ./resources

RUN npm install
RUN npx shadow-cljs release app

ARG CACHE_BUST=dev
RUN sed -i "s/__CACHE_BUST__/${CACHE_BUST}/g" resources/public/index.html
RUN clj -Sdeps '{:mvn/local-repo "./.m2/repository"}' -T:build uber

FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

COPY --from=builder /opt/target/tracker-0.0.1-standalone.jar /app/app.jar
COPY config.prod.edn /app/config.edn

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
