package io.github.sanskar121543.reconciliation.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReferenceNormalizerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "INV-000123|INV123",
            "inv 123|INV123",
            "INV000123|INV123",
            "  Inv/0123 |INV123",
            "PO-10-05|PO1005",
            "000|0",
            "A0B00C|A0B0C",
            "Rent Oct 2026|RENTOCT2026",
            "--|''",
    })
    void keepsLettersAndDigitsUpperCasedWithoutLeadingZeros(String reference, String expected) {
        assertThat(ReferenceNormalizer.normalize(reference)).isEqualTo(expected);
    }
}
