package org.yanoproject.ledger.rules.conway.gov;

import org.yanoproject.ledger.rules.conway.tx.RawVoter;
import org.yanoproject.ledger.rules.view.model.GovActionId;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the vote units read ({@code ConwayScopes.GOV_VOTES}, Gov.hs:568-608), against {@code Proposals} after the
 * transaction's proposals: the voters that do not exist (post-{@code CERTS} state), the actions the known voters vote on
 * that do not exist, and the known voters' votes on existing actions.
 *
 * @param gov            the {@code GOV} subject
 * @param unknownVoters  {@code VotersDoNotExist}'s voters, in the votes' order
 * @param unknownActions {@code GovActionsDoNotExist}'s action ids (known voters only)
 * @param known          the known voters' votes on existing actions
 */
public record VotesSubject(GovSubject gov, List<RawVoter> unknownVoters, List<GovActionId> unknownActions,
                           List<Map.Entry<RawVoter, GovAction>> known) {

    public VotesSubject {
        Objects.requireNonNull(gov, "gov");
        unknownVoters = List.copyOf(unknownVoters);
        unknownActions = List.copyOf(unknownActions);
        known = List.copyOf(known);
    }
}
