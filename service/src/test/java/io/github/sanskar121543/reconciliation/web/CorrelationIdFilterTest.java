package io.github.sanskar121543.reconciliation.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CorrelationIdFilterTest {

    @Test
    void keepsASafeClientId() {
        assertThat(CorrelationIdFilter.pick("abc-123", null)).isEqualTo("abc-123");
    }

    @Test
    void fallsBackToNginxsRequestIdThenToARandomOne() {
        assertThat(CorrelationIdFilter.pick(null, "nginx-1")).isEqualTo("nginx-1");
        assertThat(CorrelationIdFilter.pick(null, null)).hasSize(36);
    }

    @Test
    void replacesIdsThatCouldInjectIntoLogs() {
        assertThat(CorrelationIdFilter.pick("bad\nid", null)).doesNotContain("\n").hasSize(36);
        assertThat(CorrelationIdFilter.pick("x".repeat(65), null)).hasSize(36);
    }
}
