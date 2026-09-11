# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage - compile, test and package the Spring Boot jar. The build
# context is ./backend, so the paths below are relative to the backend module.
# ---------------------------------------------------------------------------
FROM maven:3.9.16-eclipse-temurin-26-noble AS build
WORKDIR /build

COPY pom.xml ./
COPY src ./src

# The BuildKit cache mount keeps the local Maven repository between builds, so
# rebuilds stay fast. dependency:go-offline is deliberately not used: it does
# not prefetch the JUnit Platform jars that Surefire resolves while running the
# tests, so the packaging step would hit the network regardless.
# The unit tests do not touch the network, so they run as part of the build.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q clean package

# ---------------------------------------------------------------------------
# Runtime stage - JRE only, no build tooling, running as a non-root user.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:26-jre-noble
WORKDIR /app

RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home app

COPY --from=build /build/target/*.jar /app/app.jar

USER app
EXPOSE 8080

# JAVA_OPTS is expanded by the shell at run time. Database credentials come
# from the environment (DB_URL, DB_USERNAME, DB_PASSWORD), never from the image.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
