# Build stage: resolve dependencies first so that layer is cached until pom.xml changes.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# Runtime stage: JRE only, non-root.
FROM eclipse-temurin:17-jre
ARG APP_COMMIT=local
ENV APP_COMMIT=${APP_COMMIT}
RUN useradd --system --uid 10001 appuser
WORKDIR /app
COPY --from=build /build/target/scan-api.jar scan-api.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/scan-api.jar"]
