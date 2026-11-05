package com.coolhandlabs.coolhand;

/**
 * A Coolhand API call failed: a network error, a timeout, a redirect (never followed, so the API key
 * can't be carried to another host) or a response that was not the expected JSON. Unchecked, like the
 * {@code Error}s coolhand-node's read methods reject with. Messages never contain the API key.
 */
public class CoolhandException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CoolhandException(String message) {
        super(message);
    }

    public CoolhandException(String message, Throwable cause) {
        super(message, cause);
    }
}
