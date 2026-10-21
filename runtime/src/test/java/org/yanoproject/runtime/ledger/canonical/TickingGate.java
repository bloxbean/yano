package org.yanoproject.runtime.ledger.canonical;

import org.rocksdb.ReadOptions;
import org.yanoproject.api.model.ProtocolParamsSnapshot;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.CredentialType;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledgerstate.AccountStateCborCodec.PoolRegistrationData;
import org.yanoproject.ledgerstate.EpochBoundaryPreview;
import org.yanoproject.ledgerstate.EpochBoundaryPreview.RewardRestKey;
import org.yanoproject.ledgerstate.LedgerStateSnapshotReader;
import org.yanoproject.ledgerstate.governance.GovernanceSnapshotReader;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore;
import org.yanoproject.ledgerstate.governance.model.CommitteeMemberRecord;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The ADR-056 ticking gate over a {@link TickingTestNode}: tick the canonical state at the ledger tip
 * to the next epoch, run the real boundary on the same store, and compare every ticked value with the
 * value the real boundary persisted.
 *
 * <p>The ticked view is created before the real boundary runs and its values are captured after it,
 * so the comparison also proves the dry run reads only its own snapshot and the in-memory inputs
 * captured with it (the live tracker has by then finalized the new epoch).</p>
 */
final class TickingGate {

    /** Which records to compare. */
    record Probes(Set<String> pools, Set<String> vrfs, Set<GovActionId> proposals, Set<CredentialKey> colds,
                  Set<CredentialKey> hots, Set<CredentialKey> dreps) {
    }

    /**
     * The outcome of one gate run.
     *
     * @param mismatches            ticked values that differ from the persisted ones (gate failures)
     * @param drepExpiryDifferences DRep expiries that differ: expected only when the boundary flushes
     *                              the dormant counter, since the ticked view passes expiry through
     */
    record Result(EpochBoundaryPreview preview, Values ticked, Values real,
                  Map<RewardRestKey, BigInteger> persistedRewardRest, Map<String, BigInteger> rewardDeltas,
                  List<String> mismatches, List<String> drepExpiryDifferences) {
    }

    /** Every compared value a view answers. */
    record Values(ProtocolParamsSnapshot protocolParams, Object cclParams, List<String> activeProposals,
                  Map<String, String> proposals, Object enactedRoots, List<CommitteeMemberState> committee,
                  Map<String, String> committeeByCold, Map<String, String> committeeByHot,
                  Set<String> candidates, String guardrail, Map<String, String> pools, Map<String, String> vrfs,
                  Map<String, String> dreps, Map<String, Long> drepExpiries) {
    }

    private TickingGate() {
    }

    /**
     * Runs the gate for the boundary into {@code ledgerEpoch + 1} of the node's current tip.
     *
     * @param extraProbes records to compare in addition to those found in the two snapshots
     * @param skipRewards resume the real boundary after its reward step (see
     *                    {@link TickingTestNode#preEpochTransition})
     */
    static Result run(TickingTestNode node, Probes extraProbes, boolean skipRewards) {
        CanonicalSnapshot before = node.acquire();
        int newEpoch = before.tip().ledgerEpoch() + 1;
        try (CanonicalLedgerView base = CanonicalLedgerView.over(before);
             TickedLedgerView ticked = TickedLedgerView.of(base, node.epochStartSlot(newEpoch) + 1)) {
            before.release();
            if (ticked.mode() != TickedLedgerView.Mode.TICKED) {
                throw new IllegalStateException("expected a ticked view, got " + ticked);
            }
            // --- The real boundary on the same store.
            node.preEpochTransition(newEpoch, skipRewards);
            Map<RewardRestKey, BigInteger> persistedRest = new TreeMap<>(TickingGate::compareKeys);
            try (ReadOptions reads = new ReadOptions()) {
                LedgerStateSnapshotReader reader = node.accounts.snapshotReader(node.db(), reads);
                for (LedgerStateSnapshotReader.RewardRestEntry entry : reader.rewardRest(newEpoch)) {
                    persistedRest.put(new RewardRestKey(entry.spendableEpoch(), entry.type(), entry.credType(),
                            entry.credHash()), entry.amount());
                }
            }
            node.postEpochTransition(newEpoch);

            CanonicalSnapshot after = node.acquire();
            try (CanonicalLedgerView real = CanonicalLedgerView.over(after)) {
                after.release();
                if (after.tip().ledgerEpoch() != newEpoch) {
                    throw new IllegalStateException("the real boundary did not complete: " + after.tip());
                }
                EpochBoundaryPreview preview = ticked.boundaryPreview().require("boundary preview");
                Probes probes = union(extraProbes, probes(ticked.snapshot(), newEpoch - 1), probes(after, newEpoch));
                Values tickedValues = capture(ticked, probes, preview.protocolParams().orElse(null));
                Values realValues = capture(real, probes, after.read("params",
                        state -> Lookup.ofNullable(state.protocolParams())).orElseThrowUnavailable().orElse(null));

                List<String> mismatches = new ArrayList<>(compare(tickedValues, realValues));
                Map<RewardRestKey, BigInteger> planned = new TreeMap<>(TickingGate::compareKeys);
                planned.putAll(preview.governance() != null ? preview.governance().rewardRestWrites() : Map.of());
                if (!planned.equals(persistedRest)) {
                    mismatches.add("reward_rest writes: ticked " + planned + " real " + persistedRest);
                }
                // Reward-balance change of every credential the dry run credits: before from the
                // pre-boundary snapshot, after from the live store.
                Set<String> credentials = new TreeSet<>(preview.poolDepositRefunds().keySet());
                credentials.addAll(preview.rewardRestCredits().keySet());
                Map<String, BigInteger> deltas = new TreeMap<>();
                for (String credential : credentials) {
                    int separator = credential.indexOf(':');
                    int type = Integer.parseInt(credential.substring(0, separator));
                    String hash = credential.substring(separator + 1);
                    BigInteger beforeBalance = ticked.snapshot().read("reward " + credential, state ->
                            Lookup.ofNullable(state.ledger().stakeAccount(type, hash).map(a -> a.reward())
                                    .orElse(BigInteger.ZERO))).require("reward before");
                    BigInteger afterBalance = node.accounts.getRewardBalance(type, hash).orElse(BigInteger.ZERO);
                    deltas.put(credential, afterBalance.subtract(beforeBalance));
                }
                List<String> expiries = new ArrayList<>();
                diffLongs(expiries, "drep expiry", tickedValues.drepExpiries(), realValues.drepExpiries());
                return new Result(preview, tickedValues, realValues, persistedRest, deltas, List.copyOf(mismatches),
                        List.copyOf(expiries));
            }
        }
    }

    // ------------------------------------------------------------------ probes

    /** Pools (with their live and history VRFs), proposals and committee credentials of a snapshot. */
    static Probes probes(CanonicalSnapshot snapshot, int epoch) {
        return snapshot.read("probes", state -> {
            Set<String> pools = new TreeSet<>();
            Set<String> vrfs = new TreeSet<>();
            Set<GovActionId> proposals = new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
            Set<CredentialKey> colds = new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
            Set<CredentialKey> hots = new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
            Set<CredentialKey> dreps = new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
            LedgerStateSnapshotReader ledger = state.ledger();
            if (ledger != null) {
                for (Map.Entry<String, PoolRegistrationData> pool : ledger.pools().entrySet()) {
                    pools.add(pool.getKey());
                    addVrf(vrfs, pool.getValue());
                    for (int active = epoch + 1; active <= epoch + 4; active++) {
                        ledger.poolParamsHistoryAtOrBefore(pool.getKey(), active).ifPresent(p -> addVrf(vrfs, p));
                    }
                }
                ledger.committeeHotKeys().forEach((cold, hot) -> {
                    colds.add(key(cold.credType(), cold.hash()));
                    hots.add(key(hot.hotCredType(), hot.hotHash()));
                });
                ledger.committeeResignations().forEach(cold -> colds.add(key(cold.credType(), cold.hash())));
                Optional<GovernanceSnapshotReader> governance = ledger.governance();
                if (governance.isPresent()) {
                    governance.get().drepStates().keySet().forEach(d -> dreps.add(key(d.credType(), d.hash())));
                    for (GovernanceSnapshotReader.StoredProposal proposal : governance.get().proposals()) {
                        proposals.add(new GovActionId(proposal.txHash(), proposal.index()));
                    }
                    for (Map.Entry<GovernanceStateStore.CredentialKey, CommitteeMemberRecord> member
                            : governance.get().committeeMembers().entrySet()) {
                        colds.add(key(member.getKey().credType(), member.getKey().hash()));
                        if (member.getValue().hasHotKey()) {
                            hots.add(key(member.getValue().hotCredType(), member.getValue().hotHash()));
                        }
                    }
                }
            }
            return Lookup.present(new Probes(pools, vrfs, proposals, colds, hots, dreps));
        }).require("probes");
    }

    private static void addVrf(Set<String> vrfs, PoolRegistrationData pool) {
        if (pool.vrfKeyHash() != null && !pool.vrfKeyHash().isBlank()) {
            vrfs.add(pool.vrfKeyHash().toLowerCase());
        }
    }

    private static CredentialKey key(int credType, String hash) {
        return new CredentialKey(CredentialType.fromTag(credType), hash);
    }

    private static Probes union(Probes... all) {
        Set<String> pools = new TreeSet<>();
        Set<String> vrfs = new TreeSet<>();
        Set<GovActionId> proposals = new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
        Set<CredentialKey> colds = new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
        Set<CredentialKey> hots = new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
        Set<CredentialKey> dreps = new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
        for (Probes p : all) {
            if (p == null) {
                continue;
            }
            pools.addAll(p.pools());
            vrfs.addAll(p.vrfs());
            proposals.addAll(p.proposals());
            colds.addAll(p.colds());
            hots.addAll(p.hots());
            dreps.addAll(p.dreps());
        }
        return new Probes(pools, vrfs, proposals, colds, hots, dreps);
    }

    // ------------------------------------------------------------------ capture and compare

    static Values capture(LedgerView view, Probes probes, ProtocolParamsSnapshot protocolParams) {
        Map<String, String> proposals = new TreeMap<>();
        for (GovActionId id : probes.proposals()) {
            proposals.put(id.toString(), describe(view.proposal(id), TickingGate::describeProposal));
        }
        Map<String, String> byCold = new TreeMap<>();
        for (CredentialKey cold : probes.colds()) {
            byCold.put(cold.toString(), describe(view.committeeMemberByCold(cold), Object::toString));
        }
        Map<String, String> byHot = new TreeMap<>();
        for (CredentialKey hot : probes.hots()) {
            byHot.put(hot.toString(), describe(view.committeeMembersByHot(hot), Object::toString));
        }
        Map<String, String> pools = new TreeMap<>();
        for (String pool : probes.pools()) {
            pools.put(pool, describe(view.pool(new PoolId(pool)), Object::toString));
        }
        Map<String, String> vrfs = new TreeMap<>();
        for (String vrf : probes.vrfs()) {
            vrfs.put(vrf, describe(view.poolByVrfKeyHash(vrf), Object::toString));
        }
        Map<String, String> dreps = new TreeMap<>();
        Map<String, Long> drepExpiries = new TreeMap<>();
        for (CredentialKey drep : probes.dreps()) {
            Lookup<DRepState> state = view.drep(drep);
            // Registration and deposit are compared; the expiry separately (passed through when ticked).
            dreps.put(drep.toString(), describe(state, d -> "registered/deposit=" + d.deposit()));
            if (state instanceof Lookup.Present<DRepState> present) {
                drepExpiries.put(drep.toString(), present.value().expiryEpoch());
            }
        }
        Set<String> candidates = new TreeSet<>();
        view.committeeCandidates().require("candidates").forEach(c -> candidates.add(c.toString()));
        return new Values(protocolParams, view.protocolParams().require("protocol parameters"),
                view.activeProposals().require("proposals").stream().map(TickingGate::describeProposal).toList(),
                proposals, view.enactedRoots().require("roots"), view.committeeMembers().require("committee"),
                byCold, byHot, candidates, describe(view.guardrailScriptHash(), Object::toString), pools, vrfs,
                dreps, drepExpiries);
    }

    private static String describeProposal(ProposalState p) {
        return p.id() + "/" + p.type() + "/prev=" + p.prevActionId() + "/proposed=" + p.proposedEpoch()
                + "/expires=" + p.expiresAfterEpoch() + "/deposit=" + p.deposit() + "/return=" + p.returnAddress();
    }

    /** Present values described, Absent as "absent"; Unavailable is a gate failure. */
    private static <T> String describe(Lookup<T> lookup, Function<T, String> present) {
        return switch (lookup) {
            case Lookup.Present<T> p -> present.apply(p.value());
            case Lookup.Absent<T> a -> "absent";
            case Lookup.Unavailable<T> u -> throw new AssertionError("unexpected unavailable read: " + u.reason());
        };
    }

    static List<String> compare(Values ticked, Values real) {
        List<String> mismatches = new ArrayList<>();
        check(mismatches, "protocol parameters snapshot", ticked.protocolParams(), real.protocolParams());
        check(mismatches, "protocol parameters (CCL)", String.valueOf(ticked.cclParams()),
                String.valueOf(real.cclParams()));
        check(mismatches, "active proposals", ticked.activeProposals(), real.activeProposals());
        diff(mismatches, "proposal", ticked.proposals(), real.proposals());
        check(mismatches, "enacted roots", ticked.enactedRoots(), real.enactedRoots());
        check(mismatches, "committee members", ticked.committee(), real.committee());
        diff(mismatches, "committee member by cold", ticked.committeeByCold(), real.committeeByCold());
        diff(mismatches, "committee members by hot", ticked.committeeByHot(), real.committeeByHot());
        check(mismatches, "committee candidates", ticked.candidates(), real.candidates());
        check(mismatches, "guardrail script hash", ticked.guardrail(), real.guardrail());
        diff(mismatches, "pool", ticked.pools(), real.pools());
        diff(mismatches, "pool by VRF", ticked.vrfs(), real.vrfs());
        diff(mismatches, "drep", ticked.dreps(), real.dreps());
        return mismatches;
    }

    private static void check(List<String> mismatches, String what, Object ticked, Object real) {
        if (!Objects.equals(ticked, real)) {
            mismatches.add(what + ": ticked " + ticked + " real " + real);
        }
    }

    private static void diff(List<String> mismatches, String what, Map<String, String> ticked,
                             Map<String, String> real) {
        for (String key : ticked.keySet()) {
            if (!Objects.equals(ticked.get(key), real.get(key))) {
                mismatches.add(what + " " + key + ": ticked " + ticked.get(key) + " real " + real.get(key));
            }
        }
    }

    private static void diffLongs(List<String> out, String what, Map<String, Long> ticked, Map<String, Long> real) {
        for (String key : ticked.keySet()) {
            if (!Objects.equals(ticked.get(key), real.get(key))) {
                out.add(what + " " + key + ": ticked " + ticked.get(key) + " real " + real.get(key));
            }
        }
    }

    private static int compareKeys(RewardRestKey a, RewardRestKey b) {
        return (a.spendableEpoch() + "/" + a.type() + "/" + a.credential())
                .compareTo(b.spendableEpoch() + "/" + b.type() + "/" + b.credential());
    }
}
