FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system simulator && useradd --system --uid 10002 --gid simulator simulator
COPY --from=build --chown=simulator:simulator /build/target/settlement-simulator-0.0.1-SNAPSHOT.jar /app/app.jar
USER simulator
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
