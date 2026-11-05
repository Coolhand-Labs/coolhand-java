package com.coolhandlabs.coolhand.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A file to upload with {@code uploadClientFile}. Uploads always land as {@code draft}.
 *
 * @param name display name (required)
 * @param fileType {@code slide_deck}, {@code report} or {@code document}
 * @param description description
 * @param file the bytes (required)
 * @param filename the file name sent with the part (required)
 * @param metadata extra key/values; non-string values are sent as JSON text
 */
public record ClientFileUpload(String name, String fileType, String description, byte[] file, String filename,
        Map<String, Object> metadata) {

    public ClientFileUpload {
        metadata = metadata == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static ClientFileUpload of(String name, byte[] file, String filename) {
        return new ClientFileUpload(name, null, null, file, filename, null);
    }
}
