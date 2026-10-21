package org.yanoproject.ledger.conformance.blueprint;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.effects.LedgerChange;
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.fixtures.blueprint.NewEpochStateDecoder;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Compares the state the harness reached (the initial state plus every applied transaction's {@code TxEffects} in an
 * {@code OverlayLedgerView}) with the vector's final {@code NewEpochState}, for vectors that stay in one epoch: then the
 * only changes between the two states are the transactions', so every difference is a difference in the effects the
 * Java side derives ({@code TxEffectsDeriver}, {@code OverlayLedgerView}) from what Haskell applied.
 *
 * <p>Compared: every unspent output (presence and the whole output), every account (deposit, reward balance, pool and
 * DRep delegation), pool (deposit, VRF key, pending retirement, staged re-registration), DRep (deposit, expiry), the
 * committee state (hot keys, resignations, terms) and the proposals (ids in order, epochs, deposit, type, parent).
 * Keys come from both sides: the final state's, the initial state's and every key an applied transaction touched.</p>
 *
 * <p><b>Not compared</b>, so a match is not full-state equality: the votes on proposals, proposal payloads beyond
 * type and parent (and their return addresses and anchors), DRep anchors and delegator sets, pool parameter bodies
 * beyond {@link #poolSummary} (pledge, cost, margin, owners, relays, metadata, reward account), the committee threshold,
 * the constitution anchor, the deposit, fee and donation totals, the stake distribution and snapshots (the Java rules
 * do not read these, and the view model does not hold them), and the treasury, the enacted roots and the dormant-epoch
 * count (the rules read them, but only an epoch boundary changes them, and a compared vector crosses none).</p>
 */
final class FinalStateCheck {

    /** The keys the harness touched: the initial state's and those of every applied transaction. */
    static final class Touched {
        final Set<Outpoint> utxo = new LinkedHashSet<>();
        final Set<CredentialKey> accounts = new LinkedHashSet<>();
        final Set<PoolId> pools = new LinkedHashSet<>();
        final Set<CredentialKey> dreps = new LinkedHashSet<>();

        void add(NewEpochStateDecoder.Keys keys) {
            utxo.addAll(keys.utxo());
            accounts.addAll(keys.accounts());
            pools.addAll(keys.pools());
            dreps.addAll(keys.dreps());
        }

        void add(TxEffects effects) {
            utxo.addAll(effects.consumed());
            effects.produced().forEach(e -> utxo.add(e.outpoint()));
            for (LedgerChange change : effects.changes()) {
                switch (change) {
                    case LedgerChange.AccountRegistered c -> accounts.add(c.credential());
                    case LedgerChange.AccountUnregistered c -> accounts.add(c.credential());
                    case LedgerChange.StakeDelegated c -> accounts.add(c.credential());
                    case LedgerChange.VoteDelegated c -> accounts.add(c.credential());
                    case LedgerChange.RewardWithdrawn c -> accounts.add(c.credential());
                    case LedgerChange.PoolRegistered c -> pools.add(c.pool());
                    case LedgerChange.PoolReregistered c -> pools.add(c.pool());
                    case LedgerChange.PoolRetirementScheduled c -> pools.add(c.pool());
                    case LedgerChange.DRepRegistered c -> dreps.add(c.credential());
                    case LedgerChange.DRepUpdated c -> dreps.add(c.credential());
                    case LedgerChange.DRepUnregistered c -> dreps.add(c.credential());
                    case LedgerChange.DRepActivityUpdated c -> dreps.add(c.credential());
                    default -> {
                    }
                }
            }
        }
    }

    private static final int MAX_REPORTED = 5;

    private FinalStateCheck() {
    }

    /** @return the differences between Haskell's final state and the harness's (empty when they agree) */
    static List<String> compare(NewEpochStateDecoder.Decoded expected, LedgerView actual, Touched touched) {
        LedgerView haskell = expected.view();
        List<String> diffs = new ArrayList<>();
        Set<Outpoint> utxo = union(expected.keys().utxo(), touched.utxo);
        for (Outpoint op : utxo) {
            compare(diffs, "utxo " + op.txHash() + "#" + op.index(), haskell.utxo(op), actual.utxo(op),
                    UtxoEntry::output);
        }
        for (CredentialKey c : union(expected.keys().accounts(), touched.accounts)) {
            compare(diffs, "account " + c, haskell.account(c), actual.account(c), Function.identity());
        }
        for (PoolId p : union(expected.keys().pools(), touched.pools)) {
            compare(diffs, "pool " + p, haskell.pool(p), actual.pool(p), FinalStateCheck::poolSummary);
        }
        for (CredentialKey d : union(expected.keys().dreps(), touched.dreps)) {
            compare(diffs, "drep " + d, haskell.drep(d), actual.drep(d), Function.identity());
        }
        Set<CommitteeMemberState> committeeHaskell = new HashSet<>(haskell.committeeMembers().require("committee"));
        Set<CommitteeMemberState> committeeActual = new HashSet<>(actual.committeeMembers().require("committee"));
        if (!committeeHaskell.equals(committeeActual)) {
            diffs.add("committee: Haskell " + committeeHaskell + ", harness " + committeeActual);
        }
        List<GovActionId> proposalsHaskell = expected.keys().proposals();
        List<GovActionId> proposalsActual = actual.activeProposals().require("proposals").stream()
                .map(ProposalState::id).toList();
        if (!proposalsHaskell.equals(proposalsActual)) {
            diffs.add("proposals: Haskell " + proposalsHaskell + ", harness " + proposalsActual);
        } else {
            for (GovActionId id : proposalsHaskell) {
                compare(diffs, "proposal " + id, haskell.proposal(id), actual.proposal(id),
                        FinalStateCheck::proposalSummary);
            }
        }
        return diffs.size() <= MAX_REPORTED ? diffs
                : List.copyOf(concat(diffs.subList(0, MAX_REPORTED), "… " + (diffs.size() - MAX_REPORTED) + " more"));
    }

    private static <T> void compare(List<String> diffs, String what, Lookup<T> haskell, Lookup<T> actual,
                                    Function<? super T, ?> summary) {
        Object h = haskell.orElseThrowUnavailable().map(summary).orElse(null);
        Object a = actual.orElseThrowUnavailable().map(summary).orElse(null);
        if (!Objects.equals(h, a)) {
            diffs.add(what + ": Haskell " + (h == null ? "absent" : h) + ", harness " + (a == null ? "absent" : a));
        }
    }

    private static Object poolSummary(PoolState p) {
        return List.of(p.deposit(), p.vrfKeyHashHex(), String.valueOf(p.retiringEpoch()),
                p.futureParams() != null ? "future params" : "no future params");
    }

    private static Object proposalSummary(ProposalState p) {
        return List.of(p.type(), p.proposedEpoch(), p.expiresAfterEpoch(), p.deposit(),
                String.valueOf(p.prevActionId()));
    }

    private static <T> Set<T> union(Set<T> a, Set<T> b) {
        Set<T> all = new LinkedHashSet<>(a);
        all.addAll(b);
        return all;
    }

    private static List<String> concat(List<String> list, String last) {
        List<String> result = new ArrayList<>(list);
        result.add(last);
        return result;
    }
}
