# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage
#
# Dependencies are resolved in their own layer, before the sources are copied,
# so that editing a .java file does not re-download the whole dependency tree.
# The Maven cache is a BuildKit cache mount, which survives between builds
# without ending up in the image.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests are not run here: they need Docker (Testcontainers), which is not
# available inside a Docker build. Run them with ./mvnw verify on the host.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package

# ---------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

# Never run as root. Also gives the staging directory a sane owner.
RUN groupadd --system docqa && useradd --system --gid docqa --create-home docqa

WORKDIR /app
COPY --from=build --chown=docqa:docqa /build/target/*.jar app.jar

USER docqa
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the container memory limit is set
# by compose/k8s, and hard-coding a heap size means the JVM ignores it.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD ["sh", "-c", "curl -fsS http://localhost:8080/actuator/health/readiness || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
