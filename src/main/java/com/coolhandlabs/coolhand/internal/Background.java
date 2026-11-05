package com.coolhandlabs.coolhand.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Daemon threads for capture finalization (decompress, parse, redact, submit) and idle timers, so
 * that work never runs on, or blocks, the host's own request thread, and never keeps a JVM alive.
 */
public final class Background {
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(factory("coolhand-java-capture"));
    private static final ScheduledExecutorService SCHEDULER = scheduler();

    private Background() {}

    private static ScheduledExecutorService scheduler() {
        ScheduledThreadPoolExecutor s = new ScheduledThreadPoolExecutor(1, factory("coolhand-java-timer"));
        s.setRemoveOnCancelPolicy(true);
        return s;
    }

    private static ThreadFactory factory(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    public static ExecutorService executor() {
        return EXECUTOR;
    }

    public static ScheduledExecutorService timers() {
        return SCHEDULER;
    }
}
