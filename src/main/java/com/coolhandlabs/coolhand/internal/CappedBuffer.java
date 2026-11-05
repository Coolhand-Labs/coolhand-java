package com.coolhandlabs.coolhand.internal;

import java.io.ByteArrayOutputStream;

/**
 * Accumulates bytes up to a cap, dropping (or partially truncating) anything beyond it instead of
 * growing without bound. Thread-safe: a capture can be finalized by a timer while the host reads.
 */
public final class CappedBuffer {
    /** Upper bound on captured or decompressed bodies, as coolhand-node's {@code MAX_DECOMPRESSED_BYTES}. */
    public static final int MAX_BYTES = 50 * 1024 * 1024;

    private final int maxBytes;
    private final Runnable onTruncate;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private boolean truncated;

    public CappedBuffer(int maxBytes, Runnable onTruncate) {
        this.maxBytes = maxBytes;
        this.onTruncate = onTruncate;
    }

    public CappedBuffer(int maxBytes) {
        this(maxBytes, null);
    }

    public synchronized void push(byte[] chunk, int offset, int length) {
        // An empty chunk carries no data, so it can't overflow the cap.
        if (length <= 0) {
            return;
        }
        int remaining = maxBytes - out.size();
        if (remaining <= 0) {
            markTruncated();
            return;
        }
        if (length > remaining) {
            out.write(chunk, offset, remaining);
            markTruncated();
        } else {
            out.write(chunk, offset, length);
        }
    }

    public void push(byte[] chunk) {
        push(chunk, 0, chunk.length);
    }

    /** True once a chunk overflowed the cap (there was more data than fit). */
    public synchronized boolean isTruncated() {
        return truncated;
    }

    public synchronized int size() {
        return out.size();
    }

    public synchronized byte[] toByteArray() {
        return out.toByteArray();
    }

    private void markTruncated() {
        if (truncated) {
            return;
        }
        truncated = true;
        if (onTruncate != null) {
            try {
                onTruncate.run();
            } catch (RuntimeException ignored) {
                // a logging callback must never break capture
            }
        }
    }
}
