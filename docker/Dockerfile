# syntax=docker/dockerfile:1.7

FROM maven:3.9.12-eclipse-temurin-21-noble AS deps

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY app/pom.xml app/pom.xml
COPY config/ config/
COPY docs/contracts/ docs/contracts/

RUN --mount=type=cache,target=/root/.m2 \
	chmod +x mvnw \
	&& ./mvnw -B -ntp -pl app -DskipTests dependency:go-offline

FROM deps AS build

COPY app/src/ app/src/

RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -pl app -DskipTests package \
	&& install -d /workspace/out \
	&& find app/target -maxdepth 1 -type f -name 'app-*.jar' ! -name '*.original' -exec cp {} /workspace/out/app.jar \;

FROM busybox:1.36.1-musl AS busybox

FROM eclipse-temurin:21.0.9_10-jre-noble AS runtime

WORKDIR /app

ENV SPRING_PROFILES_ACTIVE=prod \
	LOG_DIR=/var/log/press-nexus \
	JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0

RUN groupadd --gid 10001 appuser \
	&& useradd --uid 10001 --gid 10001 --create-home --home-dir /home/appuser --shell /usr/sbin/nologin appuser appuser \
	&& mkdir -p /var/log/press-nexus \
	&& chmod 1777 /tmp \
	&& chown -R 10001:10001 /app /home/appuser /var/log/press-nexus

COPY --from=busybox /bin/busybox /bin/busybox
COPY --from=build --chown=10001:10001 /workspace/out/app.jar /app/app.jar

USER 10001:10001

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=5 \
	CMD ["/bin/busybox", "wget", "-q", "-T", "5", "-O", "/dev/null", "http://127.0.0.1:8080/actuator/health/readiness"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
