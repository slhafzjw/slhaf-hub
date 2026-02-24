ARG BUILD_IMAGE=gradle:9.0.0-jdk17
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre

FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src src

RUN ./gradlew --no-daemon clean installDist

FROM ${RUNTIME_IMAGE}
WORKDIR /app

COPY --from=build /workspace/build/install/kotlin-scripts-host /app/kotlin-scripts-host
RUN mkdir -p /app/scripts

EXPOSE 8080

ENTRYPOINT ["/app/kotlin-scripts-host/bin/kotlin-scripts-host"]
CMD ["--port=8080", "--scripts-dir=/app/scripts"]
