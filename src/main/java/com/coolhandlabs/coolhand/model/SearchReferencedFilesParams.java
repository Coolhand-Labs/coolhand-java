package com.coolhandlabs.coolhand.model;

/**
 * Filters for {@code searchReferencedFiles} (last 90 days). See {@code docs/llm-reference-search.md}.
 *
 * @param filePathContains substring of the file path ({@code q[file_path_cont]})
 * @param createdAtGteq lower bound ({@code q[created_at_gteq]})
 * @param createdAtLteq upper bound ({@code q[created_at_lteq]})
 * @param page 1-based page
 * @param per page size
 */
public record SearchReferencedFilesParams(String filePathContains, String createdAtGteq, String createdAtLteq,
        Integer page, Integer per) {
    public static final SearchReferencedFilesParams NONE = new SearchReferencedFilesParams(null, null, null, null, null);
}
