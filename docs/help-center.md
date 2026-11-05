# Help Center

Search and read the Coolhand help center. Read-only, and **no API key is needed**.

```java
HelpCenterDocsService docs = new HelpCenterDocsService();   // anonymous, unless COOLHAND_API_KEY is set

Page<JsonNode> results = docs.searchHelpArticles(SearchHelpArticlesParams.query("declared workloads"));
Page<JsonNode> inSection = docs.searchHelpArticles(new SearchHelpArticlesParams("feedback", "guides", 1, 10));
Page<JsonNode> all = docs.searchHelpArticles(SearchHelpArticlesParams.NONE);   // every published article
JsonNode article = docs.getHelpArticle("feedback-api");                    // markdown body and toc
List<JsonNode> sections = docs.listHelpCategories();                        // slug is the category filter
```

`Coolhand` has the same three methods and sends its own key.

- A key (the config's, else `COOLHAND_API_KEY`) is sent as `X-API-Key` and lifts the anonymous limit
  of 30 requests per minute per IP. To stay anonymous with the variable set, pass an empty key:
  `new HelpCenterDocsService(ServiceConfig.builder().apiKey("").build())`.
- A query is sent as `q`; with a `category`, as `q[help_category_slug_eq]` plus `q[full_text]`
  (the endpoint has no `category` parameter). Blank values are dropped. `perPage` is sent as
  `per_page`.
- A rejected key throws `CoolhandHttpException` 401 (no anonymous retry); `429` when rate limited;
  `404` for an unknown or unpublished slug.
