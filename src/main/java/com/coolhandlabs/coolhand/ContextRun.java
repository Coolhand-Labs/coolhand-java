package com.coolhandlabs.coolhand;

/**
 * A {@link Runnable}-shaped block run under a declared context that may throw {@code E}.
 *
 * @param <E> the checked exception the block may throw
 */
@FunctionalInterface
public interface ContextRun<E extends Exception> {
    void run() throws E;
}
