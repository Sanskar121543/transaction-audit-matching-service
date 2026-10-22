package io.github.sanskar121543.reconciliation.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** For the dashboard stream and the backlog gauges: both poll Postgres while there is a reason to. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
}
