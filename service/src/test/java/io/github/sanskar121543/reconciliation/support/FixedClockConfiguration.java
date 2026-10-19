package io.github.sanskar121543.reconciliation.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Tests see "today" as {@link #TODAY}, so break ages and aging buckets are the same on every run. */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfiguration {

    public static final LocalDate TODAY = LocalDate.of(2026, 10, 31);

    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"), ZoneOffset.UTC);
    }
}
