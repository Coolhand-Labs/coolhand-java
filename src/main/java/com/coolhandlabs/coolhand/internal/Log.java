package com.coolhandlabs.coolhand.internal;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.function.Supplier;

/**
 * Logging through {@link System.Logger} (logger name {@code com.coolhandlabs.coolhand}), silent by
 * default like coolhand-node: progress messages go out at {@code INFO} only when {@code silent} is
 * off and at {@code DEBUG} otherwise, so they stay invisible under a default logging setup.
 * Warnings and errors that coolhand-node prints regardless of {@code silent} go out at
 * {@code WARNING}/{@code ERROR}.
 */
public final class Log {
    public static final String LOGGER_NAME = "com.coolhandlabs.coolhand";
    private static final Logger LOGGER = System.getLogger(LOGGER_NAME);

    private final boolean silent;

    public Log(boolean silent) {
        this.silent = silent;
    }

    public boolean silent() {
        return silent;
    }

    /** A progress message: {@code INFO} when not silent, {@code DEBUG} when silent. */
    public void info(String message) {
        LOGGER.log(silent ? Level.DEBUG : Level.INFO, message);
    }

    public void info(Supplier<String> message) {
        Level level = silent ? Level.DEBUG : Level.INFO;
        if (LOGGER.isLoggable(level)) {
            LOGGER.log(level, message.get());
        }
    }

    /** A warning shown whatever {@code silent} says (coolhand-node's unconditional {@code console.warn}). */
    public static void warn(String message) {
        LOGGER.log(Level.WARNING, message);
    }

    /** A warning shown only when not silent ({@code DEBUG} otherwise). */
    public void warnUnlessSilent(String message) {
        LOGGER.log(silent ? Level.DEBUG : Level.WARNING, message);
    }

    /** An error shown whatever {@code silent} says (coolhand-node's unconditional {@code console.error}). */
    public static void error(String message) {
        LOGGER.log(Level.ERROR, message);
    }

    public static void debug(String message, Throwable error) {
        LOGGER.log(Level.DEBUG, message, error);
    }
}
