package io.github.sanskar121543.reconciliation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ApiKeysTest {

    private static final String OPS_HASH = HexFormat.of().formatHex(ApiKeys.sha256("ops-key"));
    private static final String MCP_HASH = HexFormat.of().formatHex(ApiKeys.sha256("mcp-key"));

    private final ApiKeys apiKeys = new ApiKeys("ops:operator:" + OPS_HASH + ", mcp:readonly:" + MCP_HASH);

    @Test
    void resolvesTheClientAndRoleOwningTheKey() {
        assertThat(apiKeys.callerFor("ops-key")).contains(new ApiCaller("ops", Role.OPERATOR));
        assertThat(apiKeys.callerFor("mcp-key")).contains(new ApiCaller("mcp", Role.READ_ONLY));
    }

    @Test
    void rejectsUnknownOrMissingKeys() {
        assertThat(apiKeys.callerFor("wrong")).isEmpty();
        assertThat(apiKeys.callerFor(OPS_HASH)).as("the hash itself is not a key").isEmpty();
        assertThat(apiKeys.callerFor("")).isEmpty();
        assertThat(apiKeys.callerFor(null)).isEmpty();
    }

    @Test
    void refusesToStartWithoutKeysOrWithMalformedConfig() {
        assertThatThrownBy(() -> new ApiKeys("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApiKeys("ops:" + OPS_HASH)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApiKeys("ops:admin:" + OPS_HASH)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApiKeys("ops:operator:abcd")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApiKeys("ops:operator:" + OPS_HASH + ",ops:readonly:" + MCP_HASH))
                .isInstanceOf(IllegalStateException.class);
    }
}
