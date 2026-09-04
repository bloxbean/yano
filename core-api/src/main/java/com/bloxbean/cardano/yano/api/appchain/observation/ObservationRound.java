package com.bloxbean.cardano.yano.api.appchain.observation;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;

import java.util.List;
import java.util.Objects;

/** Explicit immutable consensus snapshot for one bounded acquisition round. */
public record ObservationRound(
        int version,
        byte[] subscriptionId,
        long roundNumber,
        ObservationAnchorType anchorType,
        long dueAnchor,
        long openingHeight,
        long reportDeadlineAnchor,
        long resultInclusionGraceHeights,
        long absoluteMaxRoundHeight,
        long resultExpiryHeight,
        byte[] definitionDigest,
        byte[] parametersDigest,
        long membershipEpoch,
        byte[] membershipDigest,
        int memberCount,
        int finalityQuorum,
        int maxByzantineMembers,
        ObservationReporterMode reporterMode,
        byte[] reporterSetDigest,
        int reporterCount,
        int reporterFaultBound,
        int reportThreshold,
        byte[] sourceSetDigest,
        byte[] policyDigest
) {
    public static final int MAX_ENCODED_BYTES = 2 * 1024;
    private static final int FIELDS = 24;

    public ObservationRound {
        if (version != ObservationCbor.VERSION || roundNumber < 0 || dueAnchor < 1
                || openingHeight < 1 || reportDeadlineAnchor < dueAnchor
                || resultInclusionGraceHeights < 0 || absoluteMaxRoundHeight < openingHeight
                || resultExpiryHeight < 0 || membershipEpoch < 0 || memberCount < 1
                || finalityQuorum < 1 || finalityQuorum > memberCount
                || maxByzantineMembers < 0 || reporterCount < 1
                || reporterFaultBound < 0 || reportThreshold < 1
                || reportThreshold > reporterCount) {
            throw new IllegalArgumentException("invalid observation round fields");
        }
        if (resultExpiryHeight > 0 && (resultExpiryHeight < openingHeight
                || resultExpiryHeight > absoluteMaxRoundHeight)) {
            throw new IllegalArgumentException("result expiry is outside the round lifetime");
        }
        if (2L * finalityQuorum - memberCount <= maxByzantineMembers
                || finalityQuorum > memberCount - maxByzantineMembers) {
            throw new IllegalArgumentException("round finality quorum violates safety or liveness");
        }
        if (2L * reportThreshold - reporterCount <= reporterFaultBound
                || reportThreshold > reporterCount - reporterFaultBound) {
            throw new IllegalArgumentException("round report threshold violates safety or liveness");
        }
        if (reporterMode == ObservationReporterMode.ACTIVE_MEMBERS
                && (reporterCount != memberCount
                || reporterFaultBound != maxByzantineMembers)) {
            throw new IllegalArgumentException(
                    "active-member reporter snapshot must match round membership and fault bound");
        }
        subscriptionId = ObservationCbor.fixed(subscriptionId, 32, "subscription id");
        anchorType = Objects.requireNonNull(anchorType, "anchorType");
        definitionDigest = ObservationCbor.fixed(definitionDigest, 32, "definition digest");
        parametersDigest = ObservationCbor.fixed(parametersDigest, 32, "parameters digest");
        membershipDigest = ObservationCbor.fixed(membershipDigest, 32, "membership digest");
        reporterMode = Objects.requireNonNull(reporterMode, "reporterMode");
        reporterSetDigest = ObservationCbor.fixed(reporterSetDigest, 32, "reporter set digest");
        sourceSetDigest = ObservationCbor.fixed(sourceSetDigest, 32, "source set digest");
        policyDigest = ObservationCbor.fixed(policyDigest, 32, "policy digest");
    }

    @Override public byte[] subscriptionId() { return subscriptionId.clone(); }
    @Override public byte[] definitionDigest() { return definitionDigest.clone(); }
    @Override public byte[] parametersDigest() { return parametersDigest.clone(); }
    @Override public byte[] membershipDigest() { return membershipDigest.clone(); }
    @Override public byte[] reporterSetDigest() { return reporterSetDigest.clone(); }
    @Override public byte[] sourceSetDigest() { return sourceSetDigest.clone(); }
    @Override public byte[] policyDigest() { return policyDigest.clone(); }

    public byte[] encode() {
        Array value = ObservationCbor.array();
        ObservationCbor.uint(value, version);
        ObservationCbor.bytes(value, subscriptionId);
        ObservationCbor.uint(value, roundNumber);
        ObservationCbor.uint(value, anchorType.code());
        ObservationCbor.uint(value, dueAnchor);
        ObservationCbor.uint(value, openingHeight);
        ObservationCbor.uint(value, reportDeadlineAnchor);
        ObservationCbor.uint(value, resultInclusionGraceHeights);
        ObservationCbor.uint(value, absoluteMaxRoundHeight);
        ObservationCbor.uint(value, resultExpiryHeight);
        ObservationCbor.bytes(value, definitionDigest);
        ObservationCbor.bytes(value, parametersDigest);
        ObservationCbor.uint(value, membershipEpoch);
        ObservationCbor.bytes(value, membershipDigest);
        ObservationCbor.uint(value, memberCount);
        ObservationCbor.uint(value, finalityQuorum);
        ObservationCbor.uint(value, maxByzantineMembers);
        ObservationCbor.uint(value, reporterMode.code());
        ObservationCbor.bytes(value, reporterSetDigest);
        ObservationCbor.uint(value, reporterCount);
        ObservationCbor.uint(value, reporterFaultBound);
        ObservationCbor.uint(value, reportThreshold);
        ObservationCbor.bytes(value, sourceSetDigest);
        ObservationCbor.bytes(value, policyDigest);
        return ObservationCbor.encode(value);
    }

    public static ObservationRound decode(byte[] bytes) {
        try {
            List<DataItem> f = ObservationCbor.decode(bytes, MAX_ENCODED_BYTES, 64, 32,
                    256, FIELDS, "round");
            ObservationRound value = new ObservationRound(
                    ObservationCbor.intValue(f.get(0)), ObservationCbor.bytesValue(f.get(1)),
                    ObservationCbor.longValue(f.get(2)),
                    ObservationAnchorType.fromCode(ObservationCbor.intValue(f.get(3))),
                    ObservationCbor.longValue(f.get(4)), ObservationCbor.longValue(f.get(5)),
                    ObservationCbor.longValue(f.get(6)), ObservationCbor.longValue(f.get(7)),
                    ObservationCbor.longValue(f.get(8)), ObservationCbor.longValue(f.get(9)),
                    ObservationCbor.bytesValue(f.get(10)), ObservationCbor.bytesValue(f.get(11)),
                    ObservationCbor.longValue(f.get(12)), ObservationCbor.bytesValue(f.get(13)),
                    ObservationCbor.intValue(f.get(14)), ObservationCbor.intValue(f.get(15)),
                    ObservationCbor.intValue(f.get(16)),
                    ObservationReporterMode.fromCode(ObservationCbor.intValue(f.get(17))),
                    ObservationCbor.bytesValue(f.get(18)), ObservationCbor.intValue(f.get(19)),
                    ObservationCbor.intValue(f.get(20)), ObservationCbor.intValue(f.get(21)),
                    ObservationCbor.bytesValue(f.get(22)), ObservationCbor.bytesValue(f.get(23)));
            ObservationCbor.canonical(bytes, value.encode(), "round");
            return value;
        } catch (RuntimeException malformed) {
            throw ObservationCbor.invalid("round");
        }
    }
}
