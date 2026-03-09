FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY app/pom.xml app/pom.xml
COPY config/ config/
COPY docs/contracts/ docs/contracts/
COPY app/src/ app/src/

RUN ./mvnw -B -ntp -pl app -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app

ENV SPRING_PROFILES_ACTIVE=prod
ENV LOG_DIR=/var/log/press-nexus

RUN useradd --system --create-home --uid 10001 appuser \
	&& mkdir -p /var/log/press-nexus \
	&& chown -R appuser:appuser /app /var/log/press-nexus

COPY --from=build /workspace/app/target/app-0.0.1-SNAPSHOT.jar /app/app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
