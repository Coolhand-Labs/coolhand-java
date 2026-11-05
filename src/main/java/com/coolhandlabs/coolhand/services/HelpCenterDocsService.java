package com.coolhandlabs.coolhand.services;

import com.coolhandlabs.coolhand.internal.JsText;
import com.coolhandlabs.coolhand.model.Page;
import com.coolhandlabs.coolhand.model.SearchHelpArticlesParams;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The Coolhand help center ({@code /api/v2/public_content}): readable with no API key. A key (the
 * config's, else {@code COOLHAND_API_KEY}) lifts the anonymous rate limit; pass an empty key to stay
 * anonymous.
 */
public final class HelpCenterDocsService extends BaseService {
    /** Anonymous unless {@code COOLHAND_API_KEY} is set. */
    public HelpCenterDocsService() {
        this(ServiceConfig.builder().build());
    }

    public HelpCenterDocsService(ServiceConfig config) {
        super(withEnvKey(config), "/api/v2/public_content", true);
    }

    private static ServiceConfig withEnvKey(ServiceConfig config) {
        String key = config.apiKey() != null ? config.apiKey() : System.getenv("COOLHAND_API_KEY");
        return new ServiceConfig(key == null ? "" : key, config.silent(), config.debug(), config.dryRun(),
                config.baseUrl(), config.requestTimeout(), config.httpClient());
    }

    public CompletableFuture<Page<JsonNode>> searchHelpArticlesAsync(SearchHelpArticlesParams params) {
        SearchHelpArticlesParams p = params == null ? SearchHelpArticlesParams.NONE : params;
        String query = p.query() == null || JsText.trim(p.query()).isEmpty() ? null : JsText.trim(p.query());
        String category = p.category() == null ? null : JsText.trim(p.category());
        Query q = new Query();
        if (category != null && !category.isEmpty()) {
            q.set("q[help_category_slug_eq]", category).set("q[full_text]", query);
        } else {
            q.set("q", query);
        }
        q.set("page", p.page()).set("per_page", p.perPage());
        return listPage(q.appendTo(apiEndpoint), "Help center", p.page(), p.perPage());
    }

    public Page<JsonNode> searchHelpArticles(SearchHelpArticlesParams params) {
        return await(searchHelpArticlesAsync(params));
    }

    /** One article by slug, with its markdown {@code body} and {@code toc}. */
    public CompletableFuture<JsonNode> getHelpArticleAsync(String slug) {
        return getJson(buildResourceUrl(slug, "getHelpArticle: slug must be a non-empty string"), "Help center");
    }

    public JsonNode getHelpArticle(String slug) {
        return await(getHelpArticleAsync(slug));
    }

    /** The help center sections; a section's {@code slug} is the {@code category} filter. */
    public CompletableFuture<List<JsonNode>> listHelpCategoriesAsync() {
        return getJson(apiEndpoint + "/categories", "Help center").thenApply(body -> Services.items(body, "Help center"));
    }

    public List<JsonNode> listHelpCategories() {
        return await(listHelpCategoriesAsync());
    }
}
