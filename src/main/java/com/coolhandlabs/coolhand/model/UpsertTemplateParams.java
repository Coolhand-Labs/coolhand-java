package com.coolhandlabs.coolhand.model;

/**
 * Parameters for {@code upsertTemplate}. The workload is given by id or by key (required when
 * creating); set at most one.
 *
 * @param key the stable key (required)
 * @param workloadId workload hashid, sent as {@code workload_id}
 * @param workloadKey workload key, sent as {@code workload_key}
 * @param name display name
 * @param reporter who is declaring
 */
public record UpsertTemplateParams(String key, String workloadId, String workloadKey, String name, String reporter) {
    public static UpsertTemplateParams key(String key) {
        return new UpsertTemplateParams(key, null, null, null, null);
    }

    public UpsertTemplateParams withWorkloadId(String workloadId) {
        return new UpsertTemplateParams(key, workloadId, null, name, reporter);
    }

    public UpsertTemplateParams withWorkloadKey(String workloadKey) {
        return new UpsertTemplateParams(key, null, workloadKey, name, reporter);
    }

    public UpsertTemplateParams withName(String name) {
        return new UpsertTemplateParams(key, workloadId, workloadKey, name, reporter);
    }

    public UpsertTemplateParams withReporter(String reporter) {
        return new UpsertTemplateParams(key, workloadId, workloadKey, name, reporter);
    }
}
