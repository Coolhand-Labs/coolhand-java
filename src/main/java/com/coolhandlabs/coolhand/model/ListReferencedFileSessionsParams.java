package com.coolhandlabs.coolhand.model;

/**
 * Parameters for {@code listReferencedFileSessions}.
 *
 * @param filePath exact file path (required)
 * @param page 1-based page
 * @param per page size
 */
public record ListReferencedFileSessionsParams(String filePath, Integer page, Integer per) {
    public static ListReferencedFileSessionsParams of(String filePath) {
        return new ListReferencedFileSessionsParams(filePath, null, null);
    }
}
