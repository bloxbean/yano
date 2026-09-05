package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCertificate;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Identity-bound, synchronous node-local signing/report journal. A report's
 * no-equivocation lock and canonical bytes enter one RocksDB WriteBatch before
 * the report can be diffused.
 */
final class ObservationJournal {
    static final int DEFAULT_MAX_ENTRIES = 100_000;
    static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

    private static final byte[] IDENTITY_KEY = new byte[]{'i'};
    private static final byte LOCK = 'l';
    private static final byte REPORT = 'p';
    private static final byte CERTIFICATE = 'c';
    private static final byte READY = 'y';
    private static final byte CANDIDATE = 'a';

    private final AppLedgerStore ledger;
    private final int maxEntries;
    private final long maxBytes;
    private int entries;
    private long bytes;

    record RoundRef(byte[] subscriptionId, long roundNumber) {
        RoundRef {
            subscriptionId = fixed(subscriptionId).clone();
        }

        @Override public byte[] subscriptionId() { return subscriptionId.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof RoundRef ref && roundNumber == ref.roundNumber
                    && Arrays.equals(subscriptionId, ref.subscriptionId);
        }
        @Override public int hashCode() {
            return 31 * Arrays.hashCode(subscriptionId) + Long.hashCode(roundNumber);
        }
    }

    ObservationJournal(AppLedgerStore ledger, byte[] chainGenesisId, String chainId,
                       byte[] localReporterKey, byte[] observationProfileDigest,
                       int maxEntries, long maxBytes) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        if (maxEntries < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("observation journal bounds must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        byte[] identity = identity(chainGenesisId, chainId, localReporterKey,
                observationProfileDigest);
        byte[] retained = ledger.observationRuntimeGet(IDENTITY_KEY);
        if (retained == null) {
            ledger.observationRuntimePutSync(IDENTITY_KEY, identity);
        } else if (!Arrays.equals(retained, identity)) {
            throw new IllegalStateException(
                    "Observation journal belongs to another chain, profile, or node identity");
        }
        List<AppLedgerStore.ObservationRuntimeEntry> retainedEntries =
                ledger.observationRuntimeScan(new byte[0], maxEntries + 2);
        if (retainedEntries.size() > maxEntries + 1) {
            throw new IllegalStateException("Retained observation journal exceeds entry bound");
        }
        for (AppLedgerStore.ObservationRuntimeEntry entry : retainedEntries) {
            if (!Arrays.equals(entry.key(), IDENTITY_KEY)) {
                entries++;
                bytes = Math.addExact(bytes, entry.key().length + entry.value().length);
            }
        }
        if (bytes > maxBytes) {
            throw new IllegalStateException("Retained observation journal exceeds byte bound");
        }
    }

    synchronized Optional<ObservationReport> localCandidate(byte[] subscriptionId,
                                                           long roundNumber) {
        byte[] encoded = ledger.observationRuntimeGet(
                roundPrefix(CANDIDATE, subscriptionId, roundNumber));
        return encoded == null ? Optional.empty()
                : Optional.of(ObservationReport.decode(encoded));
    }

    /** Pin canonical signing material before invoking even a remote signer. */
    synchronized ObservationReport prepareLocalReport(ObservationReport candidate) {
        Optional<ObservationReport> retained = localCandidate(
                candidate.subscriptionId(), candidate.roundNumber());
        if (retained.isPresent()) return retained.orElseThrow();
        byte[] key = roundPrefix(CANDIDATE, candidate.subscriptionId(), candidate.roundNumber());
        byte[] encoded = candidate.encode();
        reserve(1, key.length + encoded.length);
        ledger.observationRuntimePutSync(key, encoded);
        entries++;
        bytes += key.length + encoded.length;
        return candidate;
    }

    synchronized boolean persistReport(ObservationReport report) {
        byte[] sourceDigest = ObservationHashes.digest(report.sourceId());
        byte[] lockKey = reportKey(LOCK, report.subscriptionId(), report.roundNumber(),
                report.reporterPublicKey(), sourceDigest);
        byte[] reportKey = reportKey(REPORT, report.subscriptionId(), report.roundNumber(),
                report.reporterPublicKey(), sourceDigest);
        byte[] encoded = report.encode();
        byte[] reportDigest = ObservationHashes.digest(encoded);
        byte[] retainedLock = ledger.observationRuntimeGet(lockKey);
        if (retainedLock != null) {
            if (!Arrays.equals(retainedLock, reportDigest)) {
                throw new IllegalArgumentException(
                        "Observation report equivocation for one reporter/source/round");
            }
            byte[] retainedReport = ledger.observationRuntimeGet(reportKey);
            if (retainedReport == null || !Arrays.equals(retainedReport, encoded)) {
                throw new IllegalStateException(
                        "Observation signing lock exists without its canonical report");
            }
            return false;
        }
        reserve(2, lockKey.length + reportDigest.length + reportKey.length + encoded.length);
        ledger.observationRuntimeWriteSync(List.of(
                new AppLedgerStore.ObservationRuntimeEntry(lockKey, reportDigest),
                new AppLedgerStore.ObservationRuntimeEntry(reportKey, encoded)), List.of());
        entries += 2;
        bytes += lockKey.length + reportDigest.length + reportKey.length + encoded.length;
        return true;
    }

    synchronized boolean persistCertificate(ObservationCertificate certificate) {
        byte[] encoded = certificate.encode();
        byte[] key = certificateKey(CERTIFICATE, certificate);
        if (ledger.observationRuntimeGet(key) != null) {
            return false;
        }
        byte[] readyKey = readyKey(certificate);
        byte[] retainedReady = ledger.observationRuntimeGet(readyKey);
        boolean replaceReady = retainedReady == null
                || Arrays.compareUnsigned(certificate.digest(),
                ObservationCertificate.decode(retainedReady).digest()) < 0;
        int additionalEntries = retainedReady == null ? 2 : 1;
        long additionalBytes = key.length + encoded.length;
        if (retainedReady == null) {
            additionalBytes += readyKey.length + encoded.length;
        } else if (replaceReady) {
            additionalBytes += encoded.length - retainedReady.length;
        }
        reserve(additionalEntries, Math.max(0, additionalBytes));
        List<AppLedgerStore.ObservationRuntimeEntry> puts = new ArrayList<>();
        puts.add(new AppLedgerStore.ObservationRuntimeEntry(key, encoded));
        if (replaceReady) {
            puts.add(new AppLedgerStore.ObservationRuntimeEntry(readyKey, encoded));
        }
        ledger.observationRuntimeWriteSync(puts, List.of());
        entries += additionalEntries;
        bytes += additionalBytes;
        return true;
    }

    synchronized List<ObservationReport> reports(byte[] subscriptionId, long roundNumber,
                                                 int limit) {
        byte[] prefix = roundPrefix(REPORT, subscriptionId, roundNumber);
        return ledger.observationRuntimeScan(prefix, limit).stream()
                .map(entry -> ObservationReport.decode(entry.value())).toList();
    }

    synchronized List<ObservationCertificate> readyCertificates(int limit) {
        return ledger.observationRuntimeScan(new byte[]{READY}, limit).stream()
                .map(entry -> ObservationCertificate.decode(entry.value())).toList();
    }

    synchronized List<RoundRef> retainedRounds(int limit) {
        Set<RoundRef> rounds = new LinkedHashSet<>();
        for (byte tag : new byte[]{CANDIDATE, REPORT, CERTIFICATE}) {
            for (AppLedgerStore.ObservationRuntimeEntry entry :
                    ledger.observationRuntimeScan(new byte[]{tag}, maxEntries + 1)) {
                if (entry.key().length < 1 + 32 + Long.BYTES) {
                    throw new IllegalStateException("Malformed observation journal key");
                }
                ByteBuffer key = ByteBuffer.wrap(entry.key());
                key.get();
                byte[] subscriptionId = new byte[32];
                key.get(subscriptionId);
                rounds.add(new RoundRef(subscriptionId, key.getLong()));
                if (rounds.size() >= limit) {
                    return List.copyOf(rounds);
                }
            }
        }
        return List.copyOf(rounds);
    }

    synchronized void markTerminal(byte[] subscriptionId, long roundNumber) {
        List<byte[]> deletes = new ArrayList<>();
        long removedBytes = 0;
        int removedEntries = 0;
        for (byte tag : new byte[]{CANDIDATE, LOCK, REPORT, CERTIFICATE, READY}) {
            for (AppLedgerStore.ObservationRuntimeEntry entry : ledger.observationRuntimeScan(
                    roundPrefix(tag, subscriptionId, roundNumber), maxEntries + 1)) {
                deletes.add(entry.key());
                removedBytes += entry.key().length + entry.value().length;
                removedEntries++;
            }
        }
        if (!deletes.isEmpty()) {
            ledger.observationRuntimeWriteSync(List.of(), deletes);
            entries -= removedEntries;
            bytes -= removedBytes;
        }
    }

    synchronized int entries() {
        return entries;
    }

    synchronized long bytes() {
        return bytes;
    }

    private void reserve(int additionalEntries, long additionalBytes) {
        final long projectedBytes;
        try {
            projectedBytes = Math.addExact(bytes, additionalBytes);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("Observation journal capacity exceeded", overflow);
        }
        if ((long) entries + additionalEntries > maxEntries
                || projectedBytes > maxBytes) {
            throw new IllegalStateException("Observation journal capacity exceeded");
        }
    }

    private static byte[] identity(byte[] genesisId, String chainId, byte[] reporter,
                                   byte[] profileDigest) {
        if (genesisId == null || genesisId.length != 32 || reporter == null
                || reporter.length != 32 || profileDigest == null
                || profileDigest.length != 32) {
            throw new IllegalArgumentException("invalid observation journal identity");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write("yano/observation/journal/v1\0".getBytes(StandardCharsets.US_ASCII));
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.write(genesisId);
                byte[] chain = Objects.requireNonNull(chainId, "chainId")
                        .getBytes(StandardCharsets.UTF_8);
                out.writeInt(chain.length);
                out.write(chain);
                out.write(reporter);
                out.write(profileDigest);
            }
            return ObservationHashes.digest(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private static byte[] reportKey(byte tag, byte[] subscriptionId, long roundNumber,
                                    byte[] reporter, byte[] sourceDigest) {
        ByteBuffer key = ByteBuffer.allocate(1 + 32 + Long.BYTES + 32 + 32);
        key.put(tag).put(fixed(subscriptionId)).putLong(roundNumber)
                .put(fixed(reporter)).put(fixed(sourceDigest));
        return key.array();
    }

    private static byte[] certificateKey(byte tag, ObservationCertificate certificate) {
        ByteBuffer key = ByteBuffer.allocate(1 + 32 + Long.BYTES + 32 + 32);
        key.put(tag).put(certificate.subscriptionId()).putLong(certificate.roundNumber())
                .put(certificate.resultId()).put(certificate.digest());
        return key.array();
    }

    private static byte[] readyKey(ObservationCertificate certificate) {
        ByteBuffer key = ByteBuffer.allocate(1 + 32 + Long.BYTES + 32);
        key.put(READY).put(certificate.subscriptionId()).putLong(certificate.roundNumber())
                .put(certificate.resultId());
        return key.array();
    }

    private static byte[] roundPrefix(byte tag, byte[] subscriptionId, long roundNumber) {
        ByteBuffer key = ByteBuffer.allocate(1 + 32 + Long.BYTES);
        key.put(tag).put(fixed(subscriptionId)).putLong(roundNumber);
        return key.array();
    }

    private static byte[] fixed(byte[] value) {
        if (value == null || value.length != 32) {
            throw new IllegalArgumentException("observation journal identity must be 32 bytes");
        }
        return value;
    }
}
