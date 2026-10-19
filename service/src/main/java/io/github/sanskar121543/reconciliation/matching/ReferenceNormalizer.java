package io.github.sanskar121543.reconciliation.matching;

import java.util.Locale;

/**
 * Banks reformat references: "INV-000123" can come back as "inv 123" or "INV000123". Normalising keeps
 * letters and digits only, upper-cases them, and drops leading zeros from each run of digits, so all
 * three become "INV123".
 */
public final class ReferenceNormalizer {

    private ReferenceNormalizer() {
    }

    public static String normalize(String reference) {
        String compact = reference.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        StringBuilder out = new StringBuilder(compact.length());
        int i = 0;
        while (i < compact.length()) {
            char c = compact.charAt(i);
            if (!Character.isDigit(c)) {
                out.append(c);
                i++;
                continue;
            }
            int end = i;
            while (end < compact.length() && Character.isDigit(compact.charAt(end))) {
                end++;
            }
            int firstNonZero = i;
            while (firstNonZero < end - 1 && compact.charAt(firstNonZero) == '0') {
                firstNonZero++;
            }
            out.append(compact, firstNonZero, end);
            i = end;
        }
        return out.toString();
    }
}
