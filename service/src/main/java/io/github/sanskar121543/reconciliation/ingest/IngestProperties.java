package io.github.sanskar121543.reconciliation.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("matching.ingest")
public record IngestProperties(
        @DefaultValue("200000") int maxRows,
        @DefaultValue("32MB") DataSize maxBytes,
        @DefaultValue("1000") int maxRejectionsReported) {
}
