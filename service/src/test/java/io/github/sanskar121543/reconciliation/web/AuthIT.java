package io.github.sanskar121543.reconciliation.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.ApiResponse;
import io.github.sanskar121543.reconciliation.support.IntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class AuthIT {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    ApiClient operator;

    @BeforeEach
    void client() {
        operator = ApiClient.forPort(port);
    }

    @Test
    void everyApiPathNeedsAKey() {
        assertThat(operator.withKey(null).get("/v1/runs").status()).isEqualTo(401);
        assertThat(operator.withKey("wrong").get("/v1/runs").status()).isEqualTo(401);
        assertThat(operator.withKey(null).get("/v1/breaks").status()).isEqualTo(401);
        assertThat(operator.withKey(null).get("/v1/runs").problemType()).isEqualTo("/problems/unauthorized");
    }

    @Test
    void theDashboardAndHealthAreOpen() {
        assertThat(operator.withKey(null).get("/actuator/health").status()).isEqualTo(200);
    }

    @Test
    void aReadOnlyKeyCanReadButNotWrite() {
        ApiClient readOnly = operator.withKey(ApiClient.READ_ONLY_KEY);
        long runId = operator.createRun("auth");

        assertThat(readOnly.get("/v1/runs").status()).isEqualTo(200);
        assertThat(readOnly.get("/v1/runs/" + runId).status()).isEqualTo(200);
        ApiResponse create = readOnly.post("/v1/runs", Map.of("label", "nope"));
        assertThat(create.status()).isEqualTo(403);
        assertThat(create.problemType()).isEqualTo("/problems/forbidden");
        assertThat(readOnly.uploadLedger(runId, "external_id,account_id,booking_date,amount,reference\n").status())
                .isEqualTo(403);
    }

    // Each path is a way of spelling POST /v1/runs that a naive filter might not recognise. None may create
    // a run with the read-only key, whatever status the server picks for it.
    @ParameterizedTest
    @ValueSource(strings = {"/v1;x/runs", "/v1/runs;x", "/v1;/runs", "/%76%31/runs", "/v1/%72uns", "/v1//runs",
            "/v1/./runs", "/x/../v1/runs", "/actuator/..;/v1/runs", "/vendor/..;/v1/runs", "/index.html;/../v1/runs",
            "/V1/runs", "/v1/runs/"})
    void pathTricksDoNotBypassTheReadOnlyCheck(String path) throws IOException {
        long before = runs();

        int status = rawPost(path, ApiClient.READ_ONLY_KEY);

        assertThat(status / 100).as(path + " -> " + status).isNotEqualTo(2);
        assertThat(runs()).as(path).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/..;/v1/runs", "/vendor/..;/v1/runs", "/index.html;/../v1/runs",
            "/actuator/%2e%2e/v1/runs", "/vendor/../v1/runs"})
    void pathTricksDoNotReachTheApiWithoutAKey(String path) throws IOException {
        assertThat(rawGet(path) / 100).as(path).isNotEqualTo(2);
    }

    private long runs() {
        return jdbc.queryForObject("SELECT count(*) FROM runs", Long.class);
    }

    // java.net.http normalises some of these paths before sending, so the request is written by hand.
    private int rawPost(String path, String key) throws IOException {
        String body = "{\"label\":\"bypass\"}";
        return raw("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nX-API-Key: " + key
                + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length()
                + "\r\nConnection: close\r\n\r\n" + body);
    }

    private int rawGet(String path) throws IOException {
        return raw("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
    }

    private int raw(String request) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            String statusLine = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
            return Integer.parseInt(statusLine.substring(9, 12));
        }
    }
}
