# -------- Stage 1: Build --------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Copy wrapper and build files first for better caching
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts settings.gradle.kts
COPY build.gradle.kts build.gradle.kts
RUN chmod +x gradlew

# (Optional) warm up Gradle wrapper & configuration
RUN ./gradlew --no-daemon -v

# Copy sources and build
COPY src src
RUN ./gradlew clean bootJar --no-daemon -x test

# -------- Stage 2: Runtime --------
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /opt/app

# Non-root user
RUN useradd -r -u 10001 -g root appuser

# Install wget for healthcheck
RUN apt-get update && \
    apt-get install -y wget && \
    rm -rf /var/lib/apt/lists/*

# Copy the fat jar
COPY --from=build /workspace/build/libs/*.jar /opt/app/app.jar

ENV TZ=UTC \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=25.0 -Duser.timezone=UTC" \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080

HEALTHCHECK --interval=20s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep '"status":"UP"' || exit 1

USER appuser
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /opt/app/app.jar"]