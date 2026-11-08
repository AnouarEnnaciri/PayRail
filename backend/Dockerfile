# The builder compiles the application without running the test suite.
# Tests are executed independently by the CI workflow before the image build.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system payrail && useradd --system --uid 10001 --gid payrail payrail
COPY --from=build --chown=payrail:payrail /build/target/backend-0.0.1-SNAPSHOT.jar /app/app.jar
USER payrail
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
