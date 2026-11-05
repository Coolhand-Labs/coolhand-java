package com.coolhandlabs.coolhand;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * One LLM provider the monitor recognises: the shape of each entry in {@code api-patterns.json} and
 * in a custom {@code patternsFile}.
 *
 * @param id optional identifier
 * @param name display name, e.g. {@code "Anthropic"}
 * @param domains hostnames (a request also matches their subdomains); a {@code *} stands for one DNS label
 * @param paths path prefixes; binding only with {@code requiresPathMatch} (or across domains with
 *     {@code allowPathMatchAcrossDomains}, as substrings)
 * @param allowPathMatchAcrossDomains let {@code paths} match on any host (off by default)
 * @param requiresPathMatch a request must also hit one of {@code paths} (segment-boundary prefix)
 * @param ports ports that identify the provider on any host; honoured only with {@code requiresPathMatch}
 * @param headers header name to replacement value, applied after the default credential redaction
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiPattern(
        String id,
        String name,
        List<String> domains,
        List<String> paths,
        Boolean allowPathMatchAcrossDomains,
        Boolean requiresPathMatch,
        List<Integer> ports,
        Map<String, String> headers) {

    public ApiPattern {
        domains = domains == null ? List.of() : List.copyOf(domains);
        paths = paths == null ? null : List.copyOf(paths);
        ports = ports == null ? null : List.copyOf(ports);
        headers = headers == null ? null : Map.copyOf(headers);
    }
}
