package org.yanoproject.ledger.rules.conway.gov;

import com.bloxbean.cardano.client.transaction.spec.governance.actions.HardForkInitiationAction;

import org.yanoproject.ledger.rules.conway.tx.RawProposal;
import org.yanoproject.ledger.rules.conway.tx.RawProposal.ProtVer;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.GovPurpose;
import org.yanoproject.ledger.rules.view.model.ProposalState;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Haskell's {@code Proposals} as {@code GOV} folds it: the proposals before the transaction (the view) plus the
 * proposals of this transaction accepted so far, with the enacted roots. Not thread-safe: one per transition.
 */
public final class Proposals {

    private final LedgerView pre;
    private final Map<GovActionId, GovAction> added = new HashMap<>();
    private EnactedRoots roots;

    public Proposals(LedgerView pre) {
        this.pre = Objects.requireNonNull(pre, "pre");
    }

    public EnactedRoots roots() {
        if (roots == null) {
            roots = pre.enactedRoots().require("enacted roots");
        }
        return roots;
    }

    /** {@code proposalsLookupId}. */
    public Optional<GovAction> lookup(GovActionId id) {
        GovAction mine = added.get(id);
        if (mine != null) {
            return Optional.of(mine);
        }
        return switch (pre.proposal(id)) {
            case Lookup.Present<ProposalState> p -> Optional.of(of(p.value()));
            case Lookup.Absent<ProposalState> a -> Optional.empty();
            case Lookup.Unavailable<ProposalState> u ->
                    throw new LedgerStateUnavailableException("proposal " + id + ": " + u.reason());
        };
    }

    /**
     * {@code proposalsAddAction}: an action without a lineage is always added; one with a lineage when its parent
     * is the purpose's root or a node of the purpose's graph (a proposal of the same purpose).
     *
     * @return false when the parent is invalid (the proposal is not added)
     */
    public boolean add(GovAction action, GovActionId parent) {
        GovPurpose purpose = action.purpose();
        if (purpose != null && !Objects.equals(parent, roots().root(purpose))) {
            if (parent == null) {
                return false;
            }
            Optional<GovAction> node = lookup(parent);
            if (node.isEmpty() || node.get().purpose() != purpose) {
                return false;
            }
        }
        added.put(action.id(), action);
        return true;
    }

    private static GovAction of(ProposalState state) {
        int tag = GovRule.tagOf(state.type());
        ProtVer version = null;
        if (tag == RawProposal.HARD_FORK_INITIATION && state.action() instanceof HardForkInitiationAction hf
                && hf.getProtocolVersion() != null) {
            version = new ProtVer(hf.getProtocolVersion().getMajor(), hf.getProtocolVersion().getMinor());
        }
        return new GovAction(state.id(), tag, state.expiresAfterEpoch(), version, state.anyInSecurityGroup());
    }
}
