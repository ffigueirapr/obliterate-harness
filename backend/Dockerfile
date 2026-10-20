# syntax=docker/dockerfile:1.7
FROM gradle:9.8.1-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
RUN --mount=type=cache,target=/home/gradle/.gradle gradle dependencies -q > /dev/null || true
COPY src ./src
RUN --mount=type=cache,target=/home/gradle/.gradle gradle bootJar -x test -q

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 harness
COPY --from=build /src/build/libs/*.jar /app/app.jar
USER harness
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
