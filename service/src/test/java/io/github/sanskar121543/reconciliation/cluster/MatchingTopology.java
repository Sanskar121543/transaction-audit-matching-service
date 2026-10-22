package io.github.sanskar121543.reconciliation.cluster;

import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.KafkaProbe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The compose topology rebuilt in code: Postgres, Kafka, app containers running the jar Maven just built,
 * and nginx with the repo's real nginx.conf. Tests talk to it like any outside client.
 */
public final class MatchingTopology implements AutoCloseable {

    // SHA-256 of ApiClient.OPERATOR_KEY and ApiClient.READ_ONLY_KEY (test-only keys, as in application-test.yaml).
    private static final String API_KEY_HASHES =
            "test:operator:62af8704764faf8ea82fc61ce9c4c3908b6cb97d463a634e9e587d7c885db0ef,"
            + "mcp:readonly:0c88e963410f1f94f5a78faaff79e1b9f7b8a893c79b0b191f03cf2e6f003454";
    private static final long APP_MEMORY_BYTES = 768L * 1024 * 1024;

    private static final ImageFromDockerfile APP_IMAGE = new ImageFromDockerfile("transaction-audit-matching-it", true)
            .withFileFromPath("app.jar", packagedJar())
            .withDockerfileFromBuilder(builder -> builder
                    .from("eclipse-temurin:21-jre-noble")
                    .copy("app.jar", "/app/app.jar")
                    .entryPoint("sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar")
                    .build());

    private final Network network = Network.newNetwork();
    private final PostgreSQLContainer postgres;
    private final KafkaContainer kafka;
    private final Map<String, GenericContainer<?>> apps = new LinkedHashMap<>();
    private GenericContainer<?> nginx;
    private KafkaProbe kafkaProbe;

    private MatchingTopology() {
        // The same server flags as compose.yaml, so a killed app's open transaction is noticed the same way.
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=100",
                        "-c", "client_connection_check_interval=1s",
                        "-c", "tcp_keepalives_idle=30", "-c", "tcp_keepalives_interval=10",
                        "-c", "tcp_keepalives_count=3");
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
                .withNetwork(network)
                .withNetworkAliases("kafka")
                .withListener("kafka:19092")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m")
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    }

    /** app1 and app2, in that order, each with its own extra environment. */
    public static Map<String, Map<String, String>> apps(Map<String, String> app1Env, Map<String, String> app2Env) {
        Map<String, Map<String, String>> apps = new LinkedHashMap<>();
        apps.put("app1", app1Env);
        apps.put("app2", app2Env);
        return apps;
    }

    /**
     * Starts one app container per entry, each with its extra environment (for example smaller consumer
     * batches), then nginx, and waits until the apps' consumers share the topic's partitions.
     */
    public static MatchingTopology start(Map<String, Map<String, String>> appsWithEnv) {
        MatchingTopology topology = new MatchingTopology();
        Startables.deepStart(topology.postgres, topology.kafka).join();
        appsWithEnv.forEach((name, env) -> topology.apps.put(name, topology.newApp(name, env)));
        Startables.deepStart(topology.apps.values()).join();
        topology.nginx = new GenericContainer<>(DockerImageName.parse("nginx:1.30.6-alpine"))
                .withNetwork(topology.network)
                .withNetworkAliases("nginx")
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of("..", "nginx", "nginx.conf")),
                        "/etc/nginx/nginx.conf")
                .withExposedPorts(80)
                .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200));
        topology.nginx.start();
        topology.kafkaProbe = new KafkaProbe(topology.kafka.getBootstrapServers());
        // Readiness comes before the consumer group settles; an upload during the rebalance would let one
        // instance take lines the other is about to be assigned.
        topology.kafkaProbe.awaitStableGroup(appsWithEnv.keySet());
        return topology;
    }

    /** nginx's JSON access log, one line per request, including every upstream it tried. */
    public String nginxAccessLog() {
        return nginx.getLogs();
    }

    public ApiClient viaNginx() {
        return new ApiClient("http://" + nginx.getHost() + ":" + nginx.getMappedPort(80), ApiClient.OPERATOR_KEY);
    }

    public ApiClient direct(String appName) {
        GenericContainer<?> app = apps.get(appName);
        return new ApiClient("http://" + app.getHost() + ":" + app.getMappedPort(8080), ApiClient.OPERATOR_KEY);
    }

    public String logs(String appName) {
        return apps.get(appName).getLogs();
    }

    public KafkaProbe kafka() {
        return kafkaProbe;
    }

    public JdbcTemplate jdbc() {
        return new JdbcTemplate(new SimpleDriverDataSource(new org.postgresql.Driver(), postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword()));
    }

    /** A plain connection of its own, for a session that holds locks while the test watches the apps. */
    public Connection connection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** SIGKILL, like a crash or the OOM killer: no shutdown hooks, no graceful drain. */
    public void kill(String appName) {
        DockerClientFactory.instance().client().killContainerCmd(apps.get(appName).getContainerId()).exec();
    }

    /** Freezes the broker (docker pause): connections stay open but nothing is answered. */
    public void pauseKafka() {
        DockerClientFactory.instance().client().pauseContainerCmd(kafka.getContainerId()).exec();
    }

    public void unpauseKafka() {
        DockerClientFactory.instance().client().unpauseContainerCmd(kafka.getContainerId()).exec();
    }

    @Override
    public void close() {
        if (kafkaProbe != null) {
            kafkaProbe.close();
        }
        Stream.concat(Stream.ofNullable(nginx), apps.values().stream()).forEach(Startable::stop);
        kafka.stop();
        postgres.stop();
        network.close();
    }

    private GenericContainer<?> newApp(String name, Map<String, String> env) {
        GenericContainer<?> app = new GenericContainer<>(APP_IMAGE)
                .withNetwork(network)
                .withNetworkAliases(name)
                .withEnv("INSTANCE_ID", name)
                .withEnv("DB_URL", "jdbc:postgresql://postgres:5432/" + postgres.getDatabaseName())
                .withEnv("DB_USER", postgres.getUsername())
                .withEnv("DB_PASSWORD", postgres.getPassword())
                .withEnv("API_KEY_HASHES", API_KEY_HASHES)
                .withEnv("SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:19092")
                .withEnv("JAVA_OPTS", "-Xms384m -Xmx384m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError")
                .withEnv(env)
                .withExposedPorts(8080)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withMemory(APP_MEMORY_BYTES)
                        .withMemorySwap(APP_MEMORY_BYTES))
                .waitingFor(Wait.forHttp("/actuator/health/readiness").forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        app.dependsOn(postgres, kafka);
        return app;
    }

    private static Path packagedJar() {
        try (Stream<Path> files = Files.list(Path.of("target"))) {
            return files.filter(p -> p.getFileName().toString().matches("transaction-audit-matching-service-.*\\.jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No jar in target/: run ./mvnw verify, not just the test"));
        } catch (IOException e) {
            throw new IllegalStateException("No target/ directory: run ./mvnw verify", e);
        }
    }
}
