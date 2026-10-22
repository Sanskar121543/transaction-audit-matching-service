package io.github.sanskar121543.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpGuardTest {

    private final HttpGuard guard = new HttpGuard(new McpProperties.Http("s3cret",
            List.of("localhost", "127.0.0.1"), List.of("http://localhost", "http://127.0.0.1")));

    @Test
    void acceptsTheTokenFromALocalHostWithOrWithoutABrowserOrigin() {
        assertThatCode(() -> guard.validate(headers("Bearer s3cret", "localhost:8095", null))).doesNotThrowAnyException();
        assertThatCode(() -> guard.validate(headers("bearer s3cret", "127.0.0.1:8095", "http://localhost:6274")))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesAMissingOrWrongToken() {
        assertThat(status(headers(null, "localhost:8095", null))).isEqualTo(401);
        assertThat(status(headers("Bearer wrong", "localhost:8095", null))).isEqualTo(401);
        assertThat(status(headers("Basic s3cret", "localhost:8095", null))).isEqualTo(401);
        assertThat(status(headers("Bearer s3cret-and-more", "localhost:8095", null))).isEqualTo(401);
    }

    @Test
    void refusesARebindedHostOrAForeignOrigin() {
        assertThat(status(headers("Bearer s3cret", "attacker.example:8095", null))).isEqualTo(403);
        assertThat(status(headers("Bearer s3cret", "localhost.attacker.example", null))).isEqualTo(403);
        assertThat(status(headers("Bearer s3cret", null, null))).isEqualTo(403);
        assertThat(status(headers("Bearer s3cret", "localhost:8095", "https://attacker.example"))).isEqualTo(403);
        assertThat(status(headers("Bearer s3cret", "localhost:8095", "http://localhost.attacker.example")))
                .isEqualTo(403);
    }

    @Test
    void withoutAConfiguredTokenNothingGetsIn() {
        HttpGuard closed = new HttpGuard(new McpProperties.Http("", List.of("localhost"), List.of()));

        assertThatThrownBy(() -> closed.validate(headers("Bearer ", "localhost", null)))
                .isInstanceOf(ServerTransportSecurityException.class).hasMessageContaining("MCP_HTTP_TOKEN");
    }

    @Test
    void hostAndOriginPortsAreIgnored() {
        assertThat(HttpGuard.hostName("LocalHost:8095")).isEqualTo("localhost");
        assertThat(HttpGuard.hostName("[::1]:8095")).isEqualTo("[::1]");
        assertThat(HttpGuard.hostName("[::1]")).isEqualTo("[::1]");
        assertThat(HttpGuard.withoutPort("http://localhost:6274")).isEqualTo("http://localhost");
        assertThat(HttpGuard.withoutPort("http://localhost")).isEqualTo("http://localhost");
    }

    private int status(HeaderAccessor headers) {
        try {
            guard.validate(headers);
            return 200;
        } catch (ServerTransportSecurityException e) {
            return e.getStatusCode();
        }
    }

    private static HeaderAccessor headers(String authorization, String host, String origin) {
        Map<String, String> values = new LinkedHashMap<>();
        if (authorization != null) {
            values.put("Authorization", authorization);
        }
        if (host != null) {
            values.put("Host", host);
        }
        if (origin != null) {
            values.put("Origin", origin);
        }
        return new HeaderAccessor() {
            @Override
            public List<String> getHeader(String name) {
                return values.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                        .map(Map.Entry::getValue).toList();
            }

            @Override
            public List<String> getHeaderNames() {
                return new ArrayList<>(values.keySet());
            }
        };
    }
}
