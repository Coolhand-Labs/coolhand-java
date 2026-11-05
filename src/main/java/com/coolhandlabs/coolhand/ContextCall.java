package com.coolhandlabs.coolhand;

/**
 * A block run under a declared context: {@link java.util.concurrent.Callable}-shaped, but it may
 * only throw {@code E}, so {@link Coolhand#withContext(DeclaredContext, ContextCall)} rethrows
 * exactly what the block throws (nothing checked for a block that throws nothing checked).
 *
 * @param <T> the block's result
 * @param <E> the checked exception the block may throw
 */
@FunctionalInterface
public interface ContextCall<T, E extends Exception> {
    T call() throws E;
}
