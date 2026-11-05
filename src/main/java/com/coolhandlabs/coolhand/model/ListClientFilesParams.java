package com.coolhandlabs.coolhand.model;

/**
 * Filters for {@code listClientFiles}.
 *
 * @param status {@code draft}, {@code published} or {@code archived}
 * @param fileType {@code slide_deck}, {@code report} or {@code document}
 * @param page 1-based page
 * @param per page size (server default 25, max 100)
 */
public record ListClientFilesParams(String status, String fileType, Integer page, Integer per) {
    public static final ListClientFilesParams NONE = new ListClientFilesParams(null, null, null, null);
}
