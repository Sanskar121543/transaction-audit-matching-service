package io.github.sanskar121543.reconciliation.generator;

public final class Amounts {

    private Amounts() {
    }

    /** 123456 to "1234.56", -5 to "-0.05": the CSV format the service accepts. */
    public static String format(long cents) {
        String sign = cents < 0 ? "-" : "";
        long abs = Math.abs(cents);
        return sign + (abs / 100) + "." + String.format("%02d", abs % 100);
    }
}
