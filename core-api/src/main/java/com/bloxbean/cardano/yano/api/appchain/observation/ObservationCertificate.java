package com.bloxbean.cardano.yano.api.appchain.observation;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Self-contained canonical proof that a deterministic observation policy accepted reports. */
public record ObservationCertificate(
        int version,
        byte[] subscriptionId,
        long roundNumber,
        byte[] membershipDigest,
        byte[] definitionDigest,
        byte[] policyDigest,
        byte[] sourceSetDigest,
        List<ObservationReport> reports,
        byte[] output,
        byte[] policyTrace,
        byte[] resultId
) {
    public static final int MAX_ENCODED_BYTES = 2 * 1024 * 1024;
    public static final int MAX_REPORTS = 256;
    private static final int FIELDS = 11;

    public ObservationCertificate {
        if (version != ObservationCbor.VERSION || roundNumber < 0) {
            throw new IllegalArgumentException("invalid observation certificate fields");
        }
        subscriptionId = ObservationCbor.fixed(subscriptionId, 32, "subscription id");
        membershipDigest = ObservationCbor.fixed(membershipDigest, 32, "membership digest");
        definitionDigest = ObservationCbor.fixed(definitionDigest, 32, "definition digest");
        policyDigest = ObservationCbor.fixed(policyDigest, 32, "policy digest");
        sourceSetDigest = ObservationCbor.fixed(sourceSetDigest, 32, "source set digest");
        List<ObservationReport> source = new ArrayList<>(Objects.requireNonNull(reports, "reports"));
        if (source.isEmpty() || source.size() > MAX_REPORTS) {
            throw new IllegalArgumentException("invalid observation certificate report count");
        }
        source.forEach(report -> Objects.requireNonNull(report, "report"));
        List<ObservationReport> sorted = source.stream().sorted(REPORT_ORDER).toList();
        if (!source.equals(sorted)) {
            throw new IllegalArgumentException("observation reports are not in canonical order");
        }
        for (int index = 1; index < source.size(); index++) {
            ObservationReport previous = source.get(index - 1);
            ObservationReport current = source.get(index);
            if (Arrays.equals(previous.sourceId(), current.sourceId())
                    && Arrays.equals(previous.reporterPublicKey(), current.reporterPublicKey())) {
                throw new IllegalArgumentException("duplicate reporter/source in certificate");
            }
        }
        reports = List.copyOf(source);
        output = ObservationCbor.bounded(output, 64 * 1024, "certificate output");
        policyTrace = ObservationCbor.bounded(policyTrace, 64 * 1024, "certificate policy trace");
        resultId = ObservationCbor.fixed(resultId, 32, "certificate result id");
    }

    @Override public byte[] subscriptionId() { return subscriptionId.clone(); }
    @Override public byte[] membershipDigest() { return membershipDigest.clone(); }
    @Override public byte[] definitionDigest() { return definitionDigest.clone(); }
    @Override public byte[] policyDigest() { return policyDigest.clone(); }
    @Override public byte[] sourceSetDigest() { return sourceSetDigest.clone(); }
    @Override public List<ObservationReport> reports() { return List.copyOf(reports); }
    @Override public byte[] output() { return output.clone(); }
    @Override public byte[] policyTrace() { return policyTrace.clone(); }
    @Override public byte[] resultId() { return resultId.clone(); }

    public byte[] digest() {
        return ObservationHashes.certificateDigest(this);
    }

    public byte[] encode() {
        Array value = ObservationCbor.array();
        ObservationCbor.uint(value, version);
        ObservationCbor.bytes(value, subscriptionId);
        ObservationCbor.uint(value, roundNumber);
        ObservationCbor.bytes(value, membershipDigest);
        ObservationCbor.bytes(value, definitionDigest);
        ObservationCbor.bytes(value, policyDigest);
        ObservationCbor.bytes(value, sourceSetDigest);
        Array encodedReports = ObservationCbor.array();
        for (ObservationReport report : reports) {
            encodedReports.add(new ByteString(report.encode()));
        }
        value.add(encodedReports);
        ObservationCbor.bytes(value, output);
        ObservationCbor.bytes(value, policyTrace);
        ObservationCbor.bytes(value, resultId);
        return ObservationCbor.encode(value);
    }

    public static ObservationCertificate decode(byte[] bytes) {
        try {
            List<DataItem> f = ObservationCbor.decode(bytes, MAX_ENCODED_BYTES, 1024,
                    MAX_REPORTS, ObservationReport.MAX_ENCODED_BYTES, FIELDS, "certificate");
            List<ObservationReport> reports = new ArrayList<>();
            for (DataItem report : ((Array) f.get(7)).getDataItems()) {
                reports.add(ObservationReport.decode(ObservationCbor.bytesValue(report)));
            }
            ObservationCertificate value = new ObservationCertificate(
                    ObservationCbor.intValue(f.get(0)), ObservationCbor.bytesValue(f.get(1)),
                    ObservationCbor.longValue(f.get(2)), ObservationCbor.bytesValue(f.get(3)),
                    ObservationCbor.bytesValue(f.get(4)), ObservationCbor.bytesValue(f.get(5)),
                    ObservationCbor.bytesValue(f.get(6)), reports,
                    ObservationCbor.bytesValue(f.get(8)), ObservationCbor.bytesValue(f.get(9)),
                    ObservationCbor.bytesValue(f.get(10)));
            ObservationCbor.canonical(bytes, value.encode(), "certificate");
            return value;
        } catch (RuntimeException malformed) {
            throw ObservationCbor.invalid("certificate");
        }
    }

    private static final Comparator<ObservationReport> REPORT_ORDER = (left, right) -> {
        int source = Arrays.compareUnsigned(left.sourceId(), right.sourceId());
        return source != 0 ? source
                : Arrays.compareUnsigned(left.reporterPublicKey(), right.reporterPublicKey());
    };
}
