package com.bloxbean.cardano.yano.appchain.eutxo.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-UTXO-009 SP-M4 gate: the nullifier mirror reproduces the on-chain shard
 * roots deterministically, survives restart, is reconstructable from the id
 * set alone (order-independent), and serves verifiable membership /
 * non-membership proofs.
 */
class NullifierShardMirrorTest {

    @Test
    void claimsRouteToTheLastNibbleShardAndOnlyAffectThatShard() {
        NullifierShardMirror mirror = new NullifierShardMirror();
        byte[] shard3 = claimId(0xAA, 0x03);
        assertThat(NullifierShardMirror.shardOf(shard3)).isEqualTo(3);

        byte[][] emptyRoots = allRoots(mirror);
        mirror.insert(shard3);

        // Only shard 3 changed; every other shard still holds the empty root.
        for (int shard = 0; shard < NullifierShardMirror.SHARD_COUNT; shard++) {
            if (shard == 3) {
                assertThat(mirror.root(shard)).isNotEqualTo(emptyRoots[shard]);
            } else {
                assertThat(mirror.root(shard)).isEqualTo(emptyRoots[shard]);
            }
        }
        assertThat(mirror.contains(3, shard3)).isTrue();
        assertThat(mirror.contains(4, shard3)).isFalse();
    }

    @Test
    void reconstructionFromTheIdSetIsOrderIndependentAndMatchesTheLiveMirror() {
        List<byte[]> ids = shardIds(7, 24);
        NullifierShardMirror live = new NullifierShardMirror();
        for (byte[] id : ids) {
            live.insert(id);
        }

        // Reconstruct from a DIFFERENT order — the MPF root is a pure function
        // of the id set, so the crank-time rebuild cannot diverge.
        List<byte[]> shuffled = new ArrayList<>(ids);
        Collections.reverse(shuffled);
        byte[] reconstructed = NullifierShardMirror.reconstructShardRoot(shuffled);

        assertThat(reconstructed).isEqualTo(live.root(7));
    }

    @Test
    void aFreshMirrorReplayingTheSameSettlementsReproducesEveryRoot() {
        List<byte[]> settlements = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            settlements.add(claimId(0x40 + i, i)); // spread across shards
        }
        NullifierShardMirror first = new NullifierShardMirror();
        settlements.forEach(first::insert);
        byte[][] firstRoots = allRoots(first);

        // "Restart": a brand-new mirror driven by the same observations.
        NullifierShardMirror restarted = new NullifierShardMirror();
        settlements.forEach(restarted::insert);

        for (int shard = 0; shard < NullifierShardMirror.SHARD_COUNT; shard++) {
            assertThat(restarted.root(shard))
                    .as("shard %d after restart", shard)
                    .isEqualTo(firstRoots[shard]);
            // And an independent per-shard reconstruction agrees.
            byte[] rebuilt = NullifierShardMirror.reconstructShardRoot(
                    settlementsForShard(settlements, shard));
            assertThat(rebuilt).as("reconstruct shard %d", shard)
                    .isEqualTo(firstRoots[shard]);
        }
    }

    @Test
    void servesVerifiableMembershipAndNonMembershipProofs() {
        NullifierShardMirror mirror = new NullifierShardMirror();
        byte[] settled = claimId(0x11, 5);
        byte[] unsettled = claimId(0x22, 5);
        assertThat(NullifierShardMirror.shardOf(settled)).isEqualTo(5);
        assertThat(NullifierShardMirror.shardOf(unsettled)).isEqualTo(5);

        // Non-membership proof before settlement.
        byte[] rootBefore = mirror.root(5);
        byte[] absence = mirror.proofWire(5, settled).orElseThrow();
        assertThat(NullifierShardMirror.verifyAbsence(rootBefore, settled, absence))
                .isTrue();

        mirror.insert(settled);

        // Membership proof after settlement; the unsettled id still proves absent.
        byte[] rootAfter = mirror.root(5);
        byte[] membership = mirror.proofWire(5, settled).orElseThrow();
        assertThat(NullifierShardMirror.verifyMembership(rootAfter, settled, membership))
                .isTrue();
        byte[] stillAbsent = mirror.proofWire(5, unsettled).orElseThrow();
        assertThat(NullifierShardMirror.verifyAbsence(rootAfter, unsettled, stillAbsent))
                .isTrue();
    }

    @Test
    void insertPlanMirrorsTheOnChainFoldAndAdvancesTheRoot() {
        NullifierShardMirror mirror = new NullifierShardMirror();
        // Seed the shard so the plan runs against a non-empty root.
        List<byte[]> seed = shardIds(9, 5);
        seed.forEach(mirror::insert);
        byte[] priorRoot = mirror.root(9);

        List<byte[]> fresh = shardIds9Range(0x80, 3);
        NullifierShardMirror.InsertPlan plan = mirror.planInserts(9, fresh);

        assertThat(plan.shard()).isEqualTo(9);
        assertThat(plan.priorRoot()).isEqualTo(priorRoot);
        assertThat(plan.inserts()).hasSize(3);
        // The plan's next root is the shard root after applying the batch...
        assertThat(mirror.root(9)).isEqualTo(plan.nextRoot());
        // ...and equals an independent reconstruction over the full id set.
        List<byte[]> all = new ArrayList<>(seed);
        all.addAll(fresh);
        assertThat(NullifierShardMirror.reconstructShardRoot(all))
                .isEqualTo(plan.nextRoot());

        // Independently replay the fold: each planned proof must be the
        // non-membership proof at the running root, exactly as the on-chain
        // foldInserts checks (miss(runningRoot) then including).
        NullifierShardMirror replay = new NullifierShardMirror();
        seed.forEach(replay::insert);
        byte[] running = replay.root(9);
        assertThat(running).isEqualTo(priorRoot);
        for (NullifierShardMirror.PlannedInsert insert : plan.inserts()) {
            assertThat(NullifierShardMirror.verifyAbsence(
                    running, insert.claimId(), insert.proofWire())).isTrue();
            replay.insert(insert.claimId());
            running = replay.root(9);
        }
        assertThat(running).isEqualTo(plan.nextRoot());
    }

    @Test
    void rejectsWrongShardAndAlreadySettledInserts() {
        NullifierShardMirror mirror = new NullifierShardMirror();
        byte[] shard2 = claimId(0x33, 2);
        assertThatThrownBy(() -> mirror.planInserts(3, List.of(shard2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to shard");

        mirror.insert(shard2);
        assertThatThrownBy(() -> mirror.planInserts(2, List.of(shard2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already settled");

        assertThatThrownBy(() -> NullifierShardMirror.shardOf(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ----------------------------------------------------------

    private static byte[][] allRoots(NullifierShardMirror mirror) {
        byte[][] roots = new byte[NullifierShardMirror.SHARD_COUNT][];
        for (int shard = 0; shard < roots.length; shard++) {
            roots[shard] = mirror.root(shard);
        }
        return roots;
    }

    /** A 32-byte claim id whose last nibble selects {@code shard}. */
    private static byte[] claimId(int fill, int shard) {
        byte[] id = new byte[32];
        Arrays.fill(id, (byte) fill);
        id[31] = (byte) ((fill & 0xF0) | (shard & 0x0F));
        return id;
    }

    /** {@code count} distinct ids all belonging to {@code shard}. */
    private static List<byte[]> shardIds(int shard, int count) {
        List<byte[]> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] id = new byte[32];
            Arrays.fill(id, (byte) (0x10 + i));
            id[0] = (byte) i;
            id[15] = (byte) (i * 7);
            id[31] = (byte) (shard & 0x0F);
            ids.add(id);
        }
        return ids;
    }

    private static List<byte[]> shardIds9Range(int base, int count) {
        List<byte[]> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] id = new byte[32];
            Arrays.fill(id, (byte) (base + i));
            id[31] = 0x09;
            ids.add(id);
        }
        return ids;
    }

    private static List<byte[]> settlementsForShard(List<byte[]> all, int shard) {
        List<byte[]> forShard = new ArrayList<>();
        for (byte[] id : all) {
            if (NullifierShardMirror.shardOf(id) == shard) {
                forShard.add(id);
            }
        }
        return forShard;
    }
}
