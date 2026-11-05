package com.coolhandlabs.coolhand;

/**
 * The Coolhand API answered with a non-2xx status (coolhand-node's {@code HttpError}). The message
 * holds the first 2000 characters of the response body with the API key masked.
 */
public class CoolhandHttpException extends CoolhandException {
    private static final long serialVersionUID = 1L;

    private final int status;

    public CoolhandHttpException(String message, int status) {
        super(message);
        this.status = status;
    }

    /** The HTTP status code, e.g. {@code 401}, {@code 404}, {@code 422} or {@code 504}. */
    public int status() {
        return status;
    }
}
