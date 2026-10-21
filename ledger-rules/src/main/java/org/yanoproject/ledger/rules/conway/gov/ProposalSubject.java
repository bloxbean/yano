package org.yanoproject.ledger.rules.conway.gov;

import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.certs.CertState;
import org.yanoproject.ledger.rules.conway.tx.RawProposal;
import org.yanoproject.ledger.rules.view.model.GovActionId;

import java.util.Objects;

/**
 * What the {@code processProposal} units read ({@code ConwayScopes.GOV_PROPOSAL}, Gov.hs:483-566): one proposal, its
 * new action id, and whether {@code proposalsAddAction} accepted it (set by the {@code GOV.proposalsAddAction} step).
 */
public final class ProposalSubject {

    private final GovSubject gov;
    private final RawProposal proposal;
    private final GovActionId id;
    private final int network;
    private Boolean added;

    public ProposalSubject(GovSubject gov, RawProposal proposal, GovActionId id) {
        this.gov = Objects.requireNonNull(gov, "gov");
        this.proposal = Objects.requireNonNull(proposal, "proposal");
        this.id = Objects.requireNonNull(id, "id");
        this.network = CertState.network(gov.ctx());
    }

    public GovSubject gov() {
        return gov;
    }

    public TransitionContext ctx() {
        return gov.ctx();
    }

    public RawProposal proposal() {
        return proposal;
    }

    /** @return the id the proposal's action gets */
    public GovActionId id() {
        return id;
    }

    /** @return the ledger's network id, 0 (testnet) or 1 (mainnet) */
    public int network() {
        return network;
    }

    /** @return whether {@code proposalsAddAction} added the proposal */
    public boolean added() {
        if (added == null) {
            throw new IllegalStateException("GOV.proposalsAddAction has not run for proposal " + proposal.index());
        }
        return added;
    }

    void added(boolean value) {
        this.added = value;
    }
}
