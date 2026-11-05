package com.coolhandlabs.coolhand.model;

import java.util.Optional;

/**
 * A downloaded client file.
 *
 * @param content the raw bytes, exactly as uploaded
 * @param contentType the {@code Content-Type} detected at upload, if any
 * @param filename the filename from {@code Content-Disposition}, if any
 */
public record DownloadedClientFile(byte[] content, Optional<String> contentType, Optional<String> filename) {}
