package io.github.sanskar121543.reconciliation.common;

public final class Money {

    private Money() {
    }

    /** 123456 to "1234.56", -5 to "-0.05". */
    public static String format(long cents) {
        String sign = cents < 0 ? "-" : "";
        long abs = Math.abs(cents);
        return sign + (abs / 100) + "." + String.format("%02d", abs % 100);
    }
}
