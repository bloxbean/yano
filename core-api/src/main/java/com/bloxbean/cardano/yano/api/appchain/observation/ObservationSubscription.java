package com.bloxbean.cardano.yano.api.appchain.observation;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;

import java.util.List;
import java.util.Objects;

/** Canonical durable subscription record. */
public record ObservationSubscription(
        int version,
        byte[] subscriptionId,
        String applicationId,
        String route,
        byte[] definitionDigest,
        byte[] parameters,
        long creationHeight,
        ObservationAnchorType anchorType,
        long firstDueAnchor,
        long cadence,
        long expiryAnchor,
        int completionPolicy,
        ObservationSubscriptionStatus status,
        long nextDueAnchor,
        long nextRoundNumber,
        byte[] lastResultId
) {
    public static final int MAX_ENCODED_BYTES = 128 * 1024;
    private static final int FIELDS = 16;

    public ObservationSubscription {
        if (version != ObservationCbor.VERSION || creationHeight < 1 || firstDueAnchor < 1
                || cadence < 0 || expiryAnchor < 0 || completionPolicy < 0
                || nextDueAnchor < 0 || nextRoundNumber < 0) {
            throw new IllegalArgumentException("invalid observation subscription fields");
        }
        subscriptionId = ObservationCbor.fixed(subscriptionId, 32, "subscription id");
        applicationId = ObservationCbor.boundedText(applicationId, 128, "application id");
        route = ObservationCbor.boundedText(route, 128, "observation route");
        definitionDigest = ObservationCbor.fixed(definitionDigest, 32, "definition digest");
        parameters = ObservationCbor.bounded(parameters, 64 * 1024, "observation parameters");
        anchorType = Objects.requireNonNull(anchorType, "anchorType");
        status = Objects.requireNonNull(status, "status");
        lastResultId = lastResultId == null || lastResultId.length == 0
                ? null : ObservationCbor.fixed(lastResultId, 32, "last result id");
    }

    @Override public byte[] subscriptionId() { return subscriptionId.clone(); }
    @Override public byte[] definitionDigest() { return definitionDigest.clone(); }
    @Override public byte[] parameters() { return parameters.clone(); }
    @Override public byte[] lastResultId() { return lastResultId == null ? null : lastResultId.clone(); }

    public byte[] encode() {
        Array value = ObservationCbor.array();
        ObservationCbor.uint(value, version);
        ObservationCbor.bytes(value, subscriptionId);
        ObservationCbor.text(value, applicationId);
        ObservationCbor.text(value, route);
        ObservationCbor.bytes(value, definitionDigest);
        ObservationCbor.bytes(value, parameters);
        ObservationCbor.uint(value, creationHeight);
        ObservationCbor.uint(value, anchorType.code());
        ObservationCbor.uint(value, firstDueAnchor);
        ObservationCbor.uint(value, cadence);
        ObservationCbor.uint(value, expiryAnchor);
        ObservationCbor.uint(value, completionPolicy);
        ObservationCbor.uint(value, status.code());
        ObservationCbor.uint(value, nextDueAnchor);
        ObservationCbor.uint(value, nextRoundNumber);
        ObservationCbor.bytes(value, lastResultId == null ? new byte[0] : lastResultId);
        return ObservationCbor.encode(value);
    }

    public static ObservationSubscription decode(byte[] bytes) {
        try {
            List<DataItem> f = ObservationCbor.decode(bytes, MAX_ENCODED_BYTES, 48, 24,
                    64 * 1024, FIELDS, "subscription");
            byte[] last = ObservationCbor.bytesValue(f.get(15));
            ObservationSubscription value = new ObservationSubscription(
                    ObservationCbor.intValue(f.get(0)), ObservationCbor.bytesValue(f.get(1)),
                    ObservationCbor.textValue(f.get(2)), ObservationCbor.textValue(f.get(3)),
                    ObservationCbor.bytesValue(f.get(4)), ObservationCbor.bytesValue(f.get(5)),
                    ObservationCbor.longValue(f.get(6)),
                    ObservationAnchorType.fromCode(ObservationCbor.intValue(f.get(7))),
                    ObservationCbor.longValue(f.get(8)), ObservationCbor.longValue(f.get(9)),
                    ObservationCbor.longValue(f.get(10)), ObservationCbor.intValue(f.get(11)),
                    ObservationSubscriptionStatus.fromCode(ObservationCbor.intValue(f.get(12))),
                    ObservationCbor.longValue(f.get(13)), ObservationCbor.longValue(f.get(14)),
                    last.length == 0 ? null : last);
            ObservationCbor.canonical(bytes, value.encode(), "subscription");
            return value;
        } catch (RuntimeException malformed) {
            throw ObservationCbor.invalid("subscription");
        }
    }
}
