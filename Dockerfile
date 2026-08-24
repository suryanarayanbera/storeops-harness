# Build stage. Uses the project's own Maven wrapper, so the image doesn't depend on a
# maven:*-temurin-25 tag existing.
FROM eclipse-temurin:25-jdk AS build

WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

# Checkstyle binds to validate and SpotBugs to test-compile, so they run even with
# -DskipTests. Skip all the gates here: the harness Evaluator already ran ./mvnw clean test
# before anything got this far, and repeating it doubles the image build time.
RUN chmod +x mvnw \
    && ./mvnw -B -DskipTests -Dcheckstyle.skip -Dspotbugs.skip -Djacoco.skip clean package

# Runtime stage. JRE only, non-root, no build tooling in the final image.
FROM eclipse-temurin:25-jre

RUN useradd --system --create-home --shell /usr/sbin/nologin storeops
WORKDIR /app
COPY --from=build /build/target/storeops-api-*.jar app.jar
RUN chown storeops:storeops app.jar
USER storeops

EXPOSE 8080

# Let the JVM size its heap from the container's memory limit rather than the host's.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
