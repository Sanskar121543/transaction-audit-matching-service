package io.github.sanskar121543.reconciliation.breaks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AgingBucketTest {

    @ParameterizedTest
    @CsvSource({"-3,DAYS_0_2", "0,DAYS_0_2", "2,DAYS_0_2", "3,DAYS_3_7", "7,DAYS_3_7", "8,DAYS_8_30", "30,DAYS_8_30",
            "31,DAYS_31_PLUS", "400,DAYS_31_PLUS"})
    void bucketsByAgeInDays(long ageDays, AgingBucket expected) {
        assertThat(AgingBucket.of(ageDays)).isEqualTo(expected);
    }
}
