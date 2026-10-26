FROM maven:3.9.1-eclipse-temurin-8 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
# Keep downloaded artifacts across builds even when source changes invalidate this layer.
RUN --mount=type=cache,id=hnust-selection-maven,target=/root/.m2,sharing=locked \
    mvn -B -ntp -DskipTests package

FROM eclipse-temurin:8-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 selection \
    && useradd --uid 10001 --gid selection --no-create-home selection \
    && mkdir -p /app/var/private-files \
    && chown -R selection:selection /app
WORKDIR /app
COPY --from=build --chown=selection:selection /build/target/selection-backend-*.jar /app/app.jar
USER selection
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
