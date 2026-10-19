package io.github.sanskar121543.reconciliation.ingest;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Fails as soon as more than {@code maxBytes} have been read, so an oversized upload is never buffered. */
final class LimitedInputStream extends FilterInputStream {

    private final long maxBytes;
    private long count;

    LimitedInputStream(InputStream in, long maxBytes) {
        super(in);
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            add(1);
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int n = super.read(buffer, offset, length);
        if (n > 0) {
            add(n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        add(skipped);
        return skipped;
    }

    // Unchecked on purpose: the CSV parser wraps IOExceptions, and this must reach the controller as-is.
    private void add(long n) {
        count += n;
        if (count > maxBytes) {
            throw new PayloadTooLargeException("The upload is larger than " + maxBytes + " bytes");
        }
    }
}
