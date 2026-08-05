# Multi-stage: the build image carries a full JDK and the whole Maven repository,
# the runtime image carries a JRE and one jar. Nothing that was needed to compile
# is present in the thing that gets deployed.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer, keyed on the pom alone, so editing a
# Java file does not re-download the internet.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are skipped here deliberately: the integration suite needs its own Docker
# daemon, and docker-in-docker during an image build is a bad trade. `mvn verify`
# on a developer machine and in CI is where tests run - and CI gates the deploy.
RUN mvn -B -q -DskipTests package

# JRE, not JDK: no compiler, no jlink, no javac in production.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# curl for the healthcheck below. The Temurin images do not ship one, and a
# healthcheck that cannot run is worse than none - the container would report
# healthy forever.
RUN apk add --no-cache curl

# Never run as root. A numeric, high uid so the platform can enforce
# runAsNonRoot without resolving a name.
RUN addgroup -S app && adduser -S -u 10001 -G app app
USER app

COPY --from=build /build/target/notification-service-0.1.0.jar app.jar

EXPOSE 8080

# Liveness only. Deliberately not /readyz: readiness checks the database, and a
# brief database blip should take the container out of rotation, not convince
# Docker to kill and restart a perfectly healthy process.
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS http://localhost:8080/healthz || exit 1

# MaxRAMPercentage rather than a fixed -Xmx: free tiers hand out very different
# memory limits and the JVM should size itself to the cgroup it actually got.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
