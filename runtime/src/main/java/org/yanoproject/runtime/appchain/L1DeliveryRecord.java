package org.yanoproject.runtime.appchain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The durable per-chain L1 delivery record (app-layer ADR-038, D2, D5a, D8b). It is stored as one meta value, so
 * every transition below is a single atomic write.
 *
 * @param baseline canonical points recorded at first start or re-baseline, oldest first; never delivered, but always
 *                 proven rollback targets (I16)
 * @param window   delivered points, oldest first; its newest point is the cursor
 * @param pending  the single pending intent, or null
 * @param phase    the reconciliation phase; delivery runs only in {@code RECONCILED}
 * @param terminal a fail-closed state that survives restart, or null
 */
record L1DeliveryRecord(List<L1Point> baseline, List<L1Point> window, Intent pending, Phase phase,
                        Terminal terminal) {
    static final String META_KEY = "l1_delivery_record_v1";
    private static final int MAGIC = 0x594C4431; // YLD1
    private static final int MAX_POINTS = 100_000;
    private static final int MAX_HASH_BYTES = 64;
    private static final int MAX_TEXT_BYTES = 256;

    enum Phase { RECONCILING, RECONCILED }

    /** A pending intent: apply exactly {@code point}, or roll every phase back to {@code point} (D5a). */
    record Intent(Kind kind, L1Point point) {
        enum Kind { APPLY, ROLLBACK }

        Intent {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(point, "point");
        }
    }

    /** A persisted fail-closed state: it is left only by an operator re-baseline (D7a). */
    record Terminal(String state, String reason) {
        Terminal {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
        }
    }

    L1DeliveryRecord {
        baseline = List.copyOf(baseline);
        window = List.copyOf(window);
        Objects.requireNonNull(phase, "phase");
        if (baseline.isEmpty()) {
            throw new IllegalArgumentException("A delivery record needs a baseline history");
        }
    }

    static L1DeliveryRecord fresh(List<L1Point> baseline, Phase phase) {
        return new L1DeliveryRecord(baseline, List.of(), null, phase, null);
    }

    /** The effective cursor: the newest delivered point, else the newest baseline point (D2). */
    L1Point cursor() {
        return window.isEmpty() ? baseline.getLast() : window.getLast();
    }

    L1DeliveryRecord withPending(Intent intent) {
        return new L1DeliveryRecord(baseline, window, intent, phase, terminal);
    }

    L1DeliveryRecord withTerminal(Terminal failure) {
        return new L1DeliveryRecord(baseline, window, pending, phase, failure);
    }

    /** A terminal quarantine is persisted; a re-baseline refuses to run and never clears it (D8b rule 1). */
    boolean quarantined() {
        return terminal != null && L1DeliveryLoop.State.QUARANTINED.name().equals(terminal.state());
    }

    /** Commits the pending {@code APPLY}: the point joins the window, and the oldest points beyond capacity drop. */
    L1DeliveryRecord committed(L1Point point, int capacity) {
        List<L1Point> next = new ArrayList<>(window);
        next.add(point);
        while (next.size() > capacity) {
            next.removeFirst();
        }
        return new L1DeliveryRecord(baseline, next, null, phase, terminal);
    }

    /** Completes a rollback: {@code target} becomes the effective cursor (I17). */
    L1DeliveryRecord rolledBackTo(L1Point target) {
        int inWindow = window.indexOf(target);
        if (inWindow >= 0) {
            return new L1DeliveryRecord(baseline, window.subList(0, inWindow + 1), null, phase, terminal);
        }
        if (target.isOrigin()) {
            return new L1DeliveryRecord(List.of(L1Point.ORIGIN), List.of(), null, phase, terminal);
        }
        int inBaseline = baseline.indexOf(target);
        if (inBaseline >= 0) {
            return new L1DeliveryRecord(baseline.subList(0, inBaseline + 1), List.of(), null, phase, terminal);
        }
        throw new IllegalArgumentException("Rollback target " + target + " is not a recorded point");
    }

    byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                writePoints(out, baseline);
                writePoints(out, window);
                if (pending == null) {
                    out.writeByte(0);
                } else {
                    out.writeByte(pending.kind() == Intent.Kind.APPLY ? 1 : 2);
                    writePoint(out, pending.point());
                }
                out.writeByte(phase == Phase.RECONCILED ? 1 : 0);
                out.writeBoolean(terminal != null);
                if (terminal != null) {
                    writeText(out, terminal.state());
                    writeText(out, terminal.reason());
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("L1 delivery record encoding failed", impossible);
        }
    }

    static L1DeliveryRecord decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readInt() != MAGIC) {
                throw new IllegalArgumentException("Invalid L1 delivery record magic");
            }
            List<L1Point> baseline = readPoints(in);
            List<L1Point> window = readPoints(in);
            int pendingTag = in.readUnsignedByte();
            Intent pending = switch (pendingTag) {
                case 0 -> null;
                case 1 -> new Intent(Intent.Kind.APPLY, readPoint(in));
                case 2 -> new Intent(Intent.Kind.ROLLBACK, readPoint(in));
                default -> throw new IllegalArgumentException("Invalid L1 delivery intent");
            };
            int phaseTag = in.readUnsignedByte();
            if (phaseTag > 1) {
                throw new IllegalArgumentException("Invalid L1 delivery phase");
            }
            Phase phase = phaseTag == 1 ? Phase.RECONCILED : Phase.RECONCILING;
            Terminal terminal = in.readBoolean() ? new Terminal(readText(in), readText(in)) : null;
            if (in.read() != -1) {
                throw new IllegalArgumentException("Trailing L1 delivery record data");
            }
            return new L1DeliveryRecord(baseline, window, pending, phase, terminal);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid L1 delivery record", failure);
        }
    }

    private static void writePoints(DataOutputStream out, List<L1Point> points) throws IOException {
        out.writeInt(points.size());
        for (L1Point point : points) {
            writePoint(out, point);
        }
    }

    private static void writePoint(DataOutputStream out, L1Point point) throws IOException {
        byte[] hash = point.blockHash();
        out.writeLong(point.blockNumber());
        out.writeLong(point.slot());
        out.writeByte(hash.length);
        out.write(hash);
    }

    private static List<L1Point> readPoints(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_POINTS) {
            throw new IllegalArgumentException("Invalid L1 delivery point count");
        }
        List<L1Point> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(readPoint(in));
        }
        return points;
    }

    private static L1Point readPoint(DataInputStream in) throws IOException {
        long blockNumber = in.readLong();
        long slot = in.readLong();
        int hashLength = in.readUnsignedByte();
        if (hashLength > MAX_HASH_BYTES) {
            throw new IllegalArgumentException("Invalid L1 point hash length");
        }
        byte[] hash = in.readNBytes(hashLength);
        if (hash.length != hashLength) {
            throw new IllegalArgumentException("Truncated L1 point");
        }
        return new L1Point(blockNumber, slot, hash);
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) {
            bytes = Arrays.copyOf(bytes, MAX_TEXT_BYTES);
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Invalid L1 delivery text length");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IllegalArgumentException("Truncated L1 delivery text");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
