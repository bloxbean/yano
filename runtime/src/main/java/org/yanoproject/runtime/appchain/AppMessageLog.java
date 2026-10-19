package org.yanoproject.runtime.appchain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logger for per-message app-chain events (submits, drops, rejections), kept apart from the
 * node's block and lifecycle logging so operators can tune it on its own. Successful submits
 * log at DEBUG; proposer-side drops and rejections at INFO. Set
 * {@code quarkus.log.category."org.yanoproject.appchain.messages".level} to DEBUG to trace
 * every message, or to WARN to silence them.
 */
final class AppMessageLog {
    static final String CATEGORY = "org.yanoproject.appchain.messages";
    static final Logger LOG = LoggerFactory.getLogger(CATEGORY);

    private AppMessageLog() {
    }
}
