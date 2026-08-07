# Plan v5 Faz 5: one image, two roles — SPRING_PROFILES_ACTIVE picks "api" or
# "worker" at container start (see docker-compose.yml). Building both from one image
# keeps them from drifting apart the way two separately-maintained images would.
#
# gorev-motoru is a private GitHub Packages dependency (see pom.xml's
# github-gorev-motoru repository) — it needs a PAT with read:packages to resolve, and
# that PAT must NEVER end up baked into an image layer or committed anywhere. Build
# with BuildKit's secret mount, not a build ARG (an ARG's value persists in `docker
# history` even after the file using it is removed):
#
#   DOCKER_BUILDKIT=1 docker build \
#     --secret id=maven_settings,src=$HOME/.m2/settings.xml \
#     -t ecommerce-hub .
#
# docker-compose (Compose v2 supports the same `secrets:` mechanism) is configured
# accordingly in docker-compose.yml.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY backend ./backend
# Dependency layer cached separately from source so an app-only code change does not
# re-download the world. --mount=type=secret exposes the file only for this RUN's
# process lifetime — it is not written into the resulting layer.
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn -q -f backend/pom.xml -pl app -am dependency:go-offline
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn -q -f backend/pom.xml -pl app -am clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/backend/app/target/app-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
