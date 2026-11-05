package com.coolhandlabs.coolhand.interception;

import com.coolhandlabs.coolhand.internal.CappedBuffer;
import java.io.IOException;
import java.io.InputStream;

/**
 * Hands the host the response body unchanged while copying it into a {@link ResponseCapture}.
 * End of input completes the capture; a read error or an early close of a streamed body abandons it;
 * an early close of any other body drains the rest (up to 50 MiB) first.
 */
final class CapturingInputStream extends InputStream {
    private final InputStream in;
    private final ResponseCapture capture;
    private boolean closed;

    CapturingInputStream(InputStream in, ResponseCapture capture) {
        this.in = in == null ? InputStream.nullInputStream() : in;
        this.capture = capture;
    }

    @Override
    public int read() throws IOException {
        int b;
        try {
            b = in.read();
        } catch (IOException | RuntimeException e) {
            capture.abandoned();
            throw e;
        }
        if (b < 0) {
            capture.complete();
        } else {
            capture.onBytes(new byte[] {(byte) b}, 0, 1);
        }
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int n;
        try {
            n = in.read(buf, off, len);
        } catch (IOException | RuntimeException e) {
            capture.abandoned();
            throw e;
        }
        if (n < 0) {
            capture.complete();
        } else {
            capture.onBytes(buf, off, n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = 0;
        byte[] scratch = new byte[8192];
        while (skipped < n) {
            int r = read(scratch, 0, (int) Math.min(scratch.length, n - skipped));
            if (r < 0) {
                break;
            }
            skipped += r;
        }
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return in.available();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (!capture.isFinished()) {
                if (capture.isStreaming()) {
                    capture.abandoned();
                } else {
                    drain();
                }
            }
        } finally {
            in.close();
        }
    }

    private void drain() {
        byte[] scratch = new byte[8192];
        long drained = 0;
        try {
            while (true) {
                int n = in.read(scratch);
                if (n < 0) {
                    capture.complete();
                    return;
                }
                capture.onBytes(scratch, 0, n);
                drained += n;
                if (drained > CappedBuffer.MAX_BYTES) {
                    capture.abandoned();
                    return;
                }
            }
        } catch (IOException | RuntimeException e) {
            capture.abandoned();
        }
    }
}
