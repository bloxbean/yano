package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Height-versioned membership (ADR app-layer/006 E4.5): an ordered list of
 * epochs, each fixing the member set + finality threshold from a given block
 * height. Historical blocks are always verified against the epoch in effect at
 * their height, so retiring a key never invalidates already-finalized history
 * (catch-up, snapshots, evidence stay verifiable forever). Rotation appends an
 * epoch effective from the NEXT height; the full history persists in ledger
 * meta and wins over static config across restarts. Interim mechanism until
 * chain-governed membership (ADR 005 D6).
 */
final class MemberGroup {

    record Epoch(long fromHeight, Set<String> members, int threshold) {
        Epoch {
            Objects.requireNonNull(members, "members");
            if (fromHeight < 0 || members.isEmpty()
                    || members.size() > AppChainConfig.MAX_MEMBERS
                    || threshold < 1 || threshold > members.size()
                    || members.stream().anyMatch(key -> key == null
                    || !key.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("Invalid membership epoch v1 profile");
            }
            members = Set.copyOf(members);
        }
    }

    /** Ordered by fromHeight ascending; never empty; volatile snapshot swap. */
    private volatile List<Epoch> epochs;

    MemberGroup(Set<String> members, int threshold) {
        this.epochs = List.of(new Epoch(0, members, threshold));
    }

    // --- current epoch (new messages/votes/blocks) ---

    Set<String> members() {
        return current().members();
    }

    int threshold() {
        return current().threshold();
    }

    int size() {
        return current().members().size();
    }

    boolean contains(String publicKeyHex) {
        return current().members().contains(publicKeyHex.toLowerCase(Locale.ROOT));
    }

    private Epoch current() {
        List<Epoch> snapshot = epochs;
        return snapshot.get(snapshot.size() - 1);
    }

    // --- height-versioned views (historical verification) ---

    Epoch epochAt(long height) {
        List<Epoch> snapshot = epochs;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            if (snapshot.get(i).fromHeight() <= height) {
                return snapshot.get(i);
            }
        }
        return snapshot.get(0);
    }

    Set<String> membersAt(long height) {
        return epochAt(height).members();
    }

    int thresholdAt(long height) {
        return epochAt(height).threshold();
    }

    boolean containsAt(String publicKeyHex, long height) {
        return membersAt(height).contains(publicKeyHex.toLowerCase(Locale.ROOT));
    }

    // --- rotation ---

    /**
     * Append an epoch effective from {@code fromHeight}. If an epoch already
     * starts at that height (several admin steps before the next block), it is
     * replaced. Synchronized: admin calls are read-modify-write.
     */
    synchronized void appendEpoch(long fromHeight, Set<String> members, int threshold) {
        List<Epoch> updated = new ArrayList<>(epochs);
        while (!updated.isEmpty() && updated.get(updated.size() - 1).fromHeight() >= fromHeight) {
            updated.remove(updated.size() - 1);
        }
        updated.add(new Epoch(fromHeight, members, threshold));
        this.epochs = List.copyOf(updated);
    }

    /** Replace the whole history (loading persisted state at startup). */
    synchronized void load(List<Epoch> history) {
        if (history.isEmpty()) {
            throw new IllegalArgumentException("Epoch history must not be empty");
        }
        long previousHeight = -1;
        for (int index = 0; index < history.size(); index++) {
            Epoch epoch = history.get(index);
            if (epoch == null || index == 0 && epoch.fromHeight() != 0
                    || epoch.fromHeight() <= previousHeight) {
                throw new IllegalArgumentException(
                        "Membership epoch history must start at 0 and be strictly ordered");
            }
            previousHeight = epoch.fromHeight();
        }
        this.epochs = List.copyOf(history);
    }

    List<Epoch> history() {
        return epochs;
    }

    // --- persistence codec: "fromHeight|threshold|k1,k2;..." ---

    String encode() {
        StringBuilder sb = new StringBuilder();
        for (Epoch epoch : epochs) {
            if (sb.length() > 0) sb.append(';');
            sb.append(epoch.fromHeight()).append('|').append(epoch.threshold())
                    .append('|').append(String.join(",", epoch.members()));
        }
        return sb.toString();
    }

    static List<Epoch> decode(String encoded) {
        List<Epoch> history = new ArrayList<>();
        for (String entry : encoded.split(";")) {
            if (entry.isBlank()) continue;
            String[] parts = entry.split("\\|", 3);
            Set<String> members = new HashSet<>();
            for (String key : parts[2].split(",")) {
                if (!key.isBlank()) members.add(key.trim().toLowerCase(Locale.ROOT));
            }
            history.add(new Epoch(Long.parseLong(parts[0]), members, Integer.parseInt(parts[1])));
        }
        return history;
    }
}
