package org.yanoproject.api.appchain.observation;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact per-source quorums followed by a pure function of the complete pinned source vector. */
public final class CompleteSourceMedianPolicy implements ObservationReconciliationPolicy {
    public static final String ID = "complete-source-median-v1";
    public static final int MAX_SOURCES = 16;
    private static final BigInteger MILLION = BigInteger.valueOf(1_000_000);
    private final Parameters parameters;

    public CompleteSourceMedianPolicy(Parameters parameters) {
        this.parameters = parameters;
    }

    @Override
    public int requiredReportCount(ObservationRound round) {
        return Math.multiplyExact(round.reportThreshold(), parameters.sources.size());
    }

    public record Source(byte[] id, byte[] independenceGroup) {
        public Source {
            if (id == null || id.length != 32 || independenceGroup == null || independenceGroup.length != 32) {
                throw new IllegalArgumentException("Source and independence group must be 32 bytes");
            }
            id = id.clone();
            independenceGroup = independenceGroup.clone();
        }
        @Override public byte[] id() { return id.clone(); }
        @Override public byte[] independenceGroup() { return independenceGroup.clone(); }
    }

    public record Parameters(int scale, int minimumGroups, int deviationPpm,
                             BigInteger absoluteDeviation, BigInteger minimumValue, BigInteger maximumValue,
                             List<Source> sources) {
        public Parameters {
            if (sources == null || sources.isEmpty() || sources.size() > MAX_SOURCES
                    || minimumGroups < 1 || minimumGroups > sources.size()
                    || deviationPpm < 0 || deviationPpm > 1_000_000) {
                throw new IllegalArgumentException("Invalid complete-source policy bounds");
            }
            new ObservationFixedPoint(absoluteDeviation, scale);
            new ObservationFixedPoint(minimumValue, scale);
            new ObservationFixedPoint(maximumValue, scale);
            if (absoluteDeviation.signum() < 0 || minimumValue.compareTo(maximumValue) > 0) {
                throw new IllegalArgumentException("Invalid complete-source numeric limits");
            }
            sources = sources.stream().sorted((a, b) -> Arrays.compareUnsigned(a.id, b.id)).toList();
            HashSet<ByteBuffer> groups = new HashSet<>();
            for (int i = 0; i < sources.size(); i++) {
                if (i > 0 && Arrays.equals(sources.get(i - 1).id, sources.get(i).id)) {
                    throw new IllegalArgumentException("Duplicate required source");
                }
                groups.add(ByteBuffer.wrap(sources.get(i).independenceGroup));
            }
            if (minimumGroups > groups.size()) throw new IllegalArgumentException("Insufficient source groups");
        }

        public byte[] encode() {
            ByteBuffer out = ByteBuffer.allocate(62 + sources.size() * 64);
            out.put((byte) 1).put((byte) scale).put((byte) sources.size()).put((byte) minimumGroups);
            out.putInt(deviationPpm);
            out.put(new ObservationFixedPoint(absoluteDeviation, scale).encode());
            out.put(new ObservationFixedPoint(minimumValue, scale).encode());
            out.put(new ObservationFixedPoint(maximumValue, scale).encode());
            for (Source source : sources) out.put(source.id).put(source.independenceGroup);
            return out.array();
        }

        public byte[] digest() { return ObservationHashes.digest(encode()); }

        public byte[] sourceSetDigest() {
            byte[] domain = "yano/observation/complete-sources/v1\0".getBytes(StandardCharsets.US_ASCII);
            ByteBuffer out = ByteBuffer.allocate(domain.length + sources.size() * 64).put(domain);
            for (Source source : sources) out.put(source.id).put(source.independenceGroup);
            return ObservationHashes.digest(out.array());
        }

        public static Parameters decode(byte[] bytes) {
            if (bytes == null || bytes.length < 126 || bytes.length > 62 + 64 * MAX_SOURCES) {
                throw new IllegalArgumentException("Invalid complete-source policy encoding");
            }
            ByteBuffer in = ByteBuffer.wrap(bytes);
            if (in.get() != 1) throw new IllegalArgumentException("Unsupported complete-source policy version");
            int scale = Byte.toUnsignedInt(in.get());
            int count = Byte.toUnsignedInt(in.get());
            int minimum = Byte.toUnsignedInt(in.get());
            int ppm = in.getInt();
            if (count < 1 || count > MAX_SOURCES || bytes.length != 62 + count * 64) {
                throw new IllegalArgumentException("Invalid complete-source policy framing");
            }
            BigInteger[] numbers = new BigInteger[3];
            for (int i = 0; i < numbers.length; i++) {
                byte[] encoded = new byte[ObservationFixedPoint.ENCODED_BYTES];
                in.get(encoded);
                ObservationFixedPoint number = ObservationFixedPoint.decode(encoded);
                if (number.scale() != scale) throw new IllegalArgumentException("Policy scale mismatch");
                numbers[i] = number.units();
            }
            List<Source> sources = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                byte[] id = new byte[32];
                byte[] group = new byte[32];
                in.get(id).get(group);
                sources.add(new Source(id, group));
            }
            Parameters decoded = new Parameters(scale, minimum, ppm, numbers[0], numbers[1], numbers[2], sources);
            if (!Arrays.equals(bytes, decoded.encode())) throw new IllegalArgumentException("Noncanonical policy");
            return decoded;
        }
    }

    /** Throws on incomplete, conflicting, stale, duplicate or out-of-range source claims. */
    public byte[] reconcile(ObservationRound round, List<ObservationReport> reports) {
        if (reports.size() != requiredReportCount(round)) {
            throw new IllegalArgumentException("Incomplete source coverage");
        }
        Map<ByteBuffer, List<BigInteger>> groups = new LinkedHashMap<>();
        for (Source source : parameters.sources) {
            List<ObservationReport> claims = reports.stream()
                    .filter(report -> Arrays.equals(report.sourceId(), source.id)).toList();
            if (claims.size() != round.reportThreshold()) throw new IllegalArgumentException("Source quorum missing");
            ObservationReport first = claims.getFirst();
            HashSet<ByteBuffer> reporters = new HashSet<>();
            for (ObservationReport claim : claims) {
                if (!reporters.add(ByteBuffer.wrap(claim.reporterPublicKey()))
                        || !Arrays.equals(claim.value(), first.value())
                        || !Arrays.equals(claim.evidence(), first.evidence())
                        || !Arrays.equals(claim.sourceVersion(), first.sourceVersion())
                        || claim.sourceVersion().length == 0
                        || claim.freshnessAnchorType() != round.anchorType().code()
                        || claim.freshnessAnchor() != first.freshnessAnchor()
                        || claim.freshnessAnchor() < round.dueAnchor()
                        || claim.freshnessAnchor() > round.reportDeadlineAnchor()) {
                    throw new IllegalArgumentException("Conflicting or stale source claim");
                }
            }
            ObservationFixedPoint value = ObservationFixedPoint.decode(first.value());
            if (value.scale() != parameters.scale || value.units().compareTo(parameters.minimumValue) < 0
                    || value.units().compareTo(parameters.maximumValue) > 0) {
                throw new IllegalArgumentException("Source numeric bounds or scale mismatch");
            }
            groups.computeIfAbsent(ByteBuffer.wrap(source.independenceGroup), ignored -> new ArrayList<>())
                    .add(value.units());
        }
        List<BigInteger> values = groups.values().stream().map(CompleteSourceMedianPolicy::lowerMedian).toList();
        BigInteger preliminary = lowerMedian(values);
        // Inputs are signed 128-bit and ppm is bounded: intermediates fit within 149 bits.
        BigInteger tolerance = parameters.absoluteDeviation.max(
                preliminary.abs().multiply(BigInteger.valueOf(parameters.deviationPpm)).divide(MILLION));
        List<BigInteger> retained = values.stream()
                .filter(value -> value.subtract(preliminary).abs().compareTo(tolerance) <= 0).toList();
        if (retained.size() < parameters.minimumGroups) throw new IllegalArgumentException("Insufficient source diversity");
        return new ObservationFixedPoint(lowerMedian(retained), parameters.scale).encode();
    }

    private static BigInteger lowerMedian(List<BigInteger> values) {
        return values.stream().sorted().toList().get((values.size() - 1) / 2);
    }

    @Override
    public boolean verify(ObservationDefinition definition, ObservationRound round,
                          List<ObservationReport> reports, byte[] output, byte[] policyTrace) {
        if (!ID.equals(definition.reconciliationPolicyId()) || policyTrace.length != 0
                || !definition.certificateLocalUniqueness()
                || !Arrays.equals(definition.policyParametersDigest(), parameters.digest())
                || !Arrays.equals(round.policyDigest(), parameters.digest())
                || !Arrays.equals(round.sourceSetDigest(), parameters.sourceSetDigest())
                || definition.sourceThreshold() != parameters.sources.size()) return false;
        try {
            return Arrays.equals(output, reconcile(round, reports));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
