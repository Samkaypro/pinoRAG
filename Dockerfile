# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package \
    && cp target/*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN groupadd --system --gid 1001 pino \
    && useradd  --system --uid 1001 --gid pino --home /opt/pino pino \
    && mkdir -p /opt/pino /opt/pino/uploads \
    && chown -R pino:pino /opt/pino
WORKDIR /opt/pino
COPY --from=build --chown=pino:pino /workspace/app.jar app.jar
VOLUME ["/opt/pino/uploads"]

USER pino
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -Djava.security.egd=file:/dev/./urandom"
ENV SPRING_PROFILES_ACTIVE=prod

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
