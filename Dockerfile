# Build stage
FROM eclipse-temurin:25-jdk AS build

WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

RUN chmod +x mvnw \
    && ./mvnw -B -DskipTests -Dcheckstyle.skip -Dspotbugs.skip -Djacoco.skip clean package

# Runtime stage
FROM eclipse-temurin:25-jre

RUN useradd --system --create-home --shell /usr/sbin/nologin storeops
WORKDIR /app
COPY --from=build /build/target/storeops-api-*.jar app.jar
RUN chown storeops:storeops app.jar
USER storeops

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]