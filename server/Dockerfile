# Big Book server image. Build the jar first (./gradlew :server:bootJar); the base image tag
# comes from deploy/versions.env, e.g. --build-arg TEMURIN_VERSION=... (deploy/compose/build.yml does both).
ARG TEMURIN_VERSION

FROM eclipse-temurin:${TEMURIN_VERSION} AS layers
WORKDIR /layers
COPY build/libs/bigbook-server.jar bigbook-server.jar
RUN java -Djarmode=tools -jar bigbook-server.jar extract --layers --destination extracted

FROM eclipse-temurin:${TEMURIN_VERSION}
RUN addgroup -S bigbook && adduser -S -G bigbook bigbook
WORKDIR /opt/bigbook
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
USER bigbook
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "bigbook-server.jar"]
