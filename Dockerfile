FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src/main ./src/main

RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 helpdesk \
    && useradd --system --uid 10001 --gid helpdesk --home-dir /nonexistent \
       --shell /usr/sbin/nologin helpdesk

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/build/libs/devdesk-ai-1.0.0.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"
EXPOSE 8080
USER 10001:10001

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl --fail --silent --show-error --max-time 2 http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
