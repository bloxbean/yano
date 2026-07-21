package com.bloxbean.cardano.yano.appchain.composite.contracts;

/** Bounded aggregate-query wire limits compatible with the Yano host transport. */
public record AggregateQueryLimitsV1(
        int maxSubqueries,
        int maxParameterBytes,
        int maxResponseBytes
) {
    public static final int HOST_MAX_REQUEST_BYTES = 64 * 1024;
    public static final int HOST_MAX_RESPONSE_BYTES = 1024 * 1024;
    public static final AggregateQueryLimitsV1 DEFAULT =
            new AggregateQueryLimitsV1(16, HOST_MAX_REQUEST_BYTES, HOST_MAX_RESPONSE_BYTES);

    public AggregateQueryLimitsV1 {
        if (maxSubqueries < 1 || maxSubqueries > 64) {
            throw new IllegalArgumentException("maxSubqueries must be between 1 and 64");
        }
        if (maxParameterBytes < 1 || maxParameterBytes > HOST_MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("maxParameterBytes must be between 1 and 65536");
        }
        if (maxResponseBytes < 1 || maxResponseBytes > HOST_MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("maxResponseBytes must be between 1 and 1048576");
        }
    }
}
