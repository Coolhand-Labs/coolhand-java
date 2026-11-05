package com.coolhandlabs.coolhand.model;

/**
 * Parameters for {@code upsertWorkload}. Only the fields given are sent; {@code key} is immutable and
 * a new {@code name} renames.
 *
 * @param key the stable key (required)
 * @param name display name
 * @param description at most 1000 characters
 * @param reporter who is declaring
 */
public record UpsertWorkloadParams(String key, String name, String description, String reporter) {
    public static UpsertWorkloadParams key(String key) {
        return new UpsertWorkloadParams(key, null, null, null);
    }

    public UpsertWorkloadParams withName(String name) {
        return new UpsertWorkloadParams(key, name, description, reporter);
    }

    public UpsertWorkloadParams withDescription(String description) {
        return new UpsertWorkloadParams(key, name, description, reporter);
    }

    public UpsertWorkloadParams withReporter(String reporter) {
        return new UpsertWorkloadParams(key, name, description, reporter);
    }
}
