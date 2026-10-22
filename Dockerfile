# Stage 1: build the jars with the Maven wrapper, so `docker compose up --build` needs no local JDK.
# Every module is built: the service's tests use the generator, and test classes are still compiled.
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY generator/pom.xml generator/
COPY service/pom.xml service/
COPY mcp-server/pom.xml mcp-server/
RUN sh mvnw -B -q dependency:go-offline
COPY generator/src/ generator/src/
COPY service/src/ service/src/
COPY mcp-server/src/ mcp-server/src/
RUN sh mvnw -B -q package -DskipTests \
    && cp service/target/transaction-audit-matching-service-*.jar app.jar \
    && cp mcp-server/target/mcp-server-*.jar mcp.jar

# The MCP server (compose builds it with `target: mcp`): a slim JRE image that runs as a non-root user.
FROM eclipse-temurin:21-jre-noble AS mcp
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/mcp.jar mcp.jar
USER app
EXPOSE 8090
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/mcp.jar"]

# The matching service: the last stage, so a plain `docker build .` produces it.
FROM eclipse-temurin:21-jre-noble AS app
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
