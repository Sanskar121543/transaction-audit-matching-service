package io.github.sanskar121543.reconciliation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TransactionAuditMatchingApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionAuditMatchingApplication.class, args);
    }
}
