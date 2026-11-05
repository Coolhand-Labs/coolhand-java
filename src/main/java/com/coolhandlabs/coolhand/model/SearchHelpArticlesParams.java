package com.coolhandlabs.coolhand.model;

/**
 * Parameters for {@code searchHelpArticles}. Without {@code query} every published article is listed.
 *
 * @param query relevance search text
 * @param category a section slug from {@code listHelpCategories}
 * @param page 1-based page
 * @param perPage page size
 */
public record SearchHelpArticlesParams(String query, String category, Integer page, Integer perPage) {
    public static final SearchHelpArticlesParams NONE = new SearchHelpArticlesParams(null, null, null, null);

    public static SearchHelpArticlesParams query(String query) {
        return new SearchHelpArticlesParams(query, null, null, null);
    }
}
