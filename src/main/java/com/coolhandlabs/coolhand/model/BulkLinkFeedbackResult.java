package com.coolhandlabs.coolhand.model;

import java.util.List;

/**
 * Totals of a bulk link, merged across batches of 100.
 *
 * @param linked newly linked
 * @param alreadyLinked already linked before
 * @param errored failed
 * @param notFound feedback ids that do not exist
 */
public record BulkLinkFeedbackResult(long linked, long alreadyLinked, long errored, List<String> notFound) {
    public BulkLinkFeedbackResult {
        notFound = List.copyOf(notFound);
    }
}
