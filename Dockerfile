ARG BUILD_IMAGE=gradle:9.0.0-jdk17
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre

FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src src

RUN gradle --no-daemon clean installDist

FROM ${RUNTIME_IMAGE}
WORKDIR /app

COPY --from=build /workspace/build/install/slhaf-hub /app/slhaf-hub
RUN mkdir -p /app/scripts

EXPOSE 8080

ENTRYPOINT ["/app/slhaf-hub/bin/slhaf-hub"]
CMD ["--host=0.0.0.0", "--port=8080", "--scripts-dir=/app/scripts"]
