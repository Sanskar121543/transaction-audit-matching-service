package io.github.sanskar121543.reconciliation.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@ActiveProfiles("test")
// Tests switch metrics export off by default; MetricsIT scrapes /actuator/prometheus like Prometheus does. On
// every integration test, not just that one, so they all share one application context.
@AutoConfigureMetrics
public @interface IntegrationTest {
}
