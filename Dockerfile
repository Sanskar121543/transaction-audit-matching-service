# Stage 1: build the jar with the Maven wrapper, so `docker compose up --build` needs no local JDK.
# Both modules are built: the service's tests use the generator, and test classes are still compiled.
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY generator/pom.xml generator/
COPY service/pom.xml service/
RUN sh mvnw -B -q dependency:go-offline
COPY generator/src/ generator/src/
COPY service/src/ service/src/
RUN sh mvnw -B -q package -DskipTests && cp service/target/transaction-audit-matching-service-*.jar app.jar

# Stage 2: a slim JRE image that runs as a non-root user.
FROM eclipse-temurin:21-jre-noble
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
