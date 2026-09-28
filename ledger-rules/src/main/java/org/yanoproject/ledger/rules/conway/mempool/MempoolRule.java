package org.yanoproject.ledger.rules.conway.mempool;

import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedures;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.CredentialType;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.Voter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Conway {@code MEMPOOL} rule's own checks (ADR-056 §4 step 0), run for admission and mempool
 * rebuilds against the <em>incoming</em> ledger state, before {@code LEDGER}.
 *
 * <p>Mirrors {@code Cardano.Ledger.Conway.Rules.Mempool.mempoolTransition} at cardano-ledger
 * {@code f649f975} ({@code Conway/Rules/Mempool.hs:103-138}):</p>
 * <ol>
 *   <li>If none of the transaction's <em>spending</em> inputs is in the UTxO
 *       ({@code notAllSpent = any (`Map.member` utxo) inputs}), fail with
 *       {@code ConwayMempoolFailure "All inputs are spent. Transaction has probably already been
 *       included"}. Reference and collateral inputs are not considered, and an empty input set counts
 *       as all spent ({@code any} of nothing is false). Everything else is then skipped
 *       ({@code whenFailureFreeDefault}), so a duplicate reports only this failure.</li>
 *   <li>Otherwise, while {@code hardforkConwayDisallowUnelectedCommitteeFromVoting} is off
 *       (protocol major version &le; 10, {@code Conway/Era.hs:262-263}): votes cast by committee hot
 *       credentials that are not authorised by an <em>elected</em> member
 *       ({@code unelectedCommitteeVoters}, {@code Gov.hs:652-665};
 *       {@code authorizedElectedHotCommitteeCredentials}, {@code Governance.hs:581-591}) fail with
 *       {@code ConwayMempoolFailure "Unelected committee members are not allowed to cast votes: …"}.
 *       This check does not stop {@code LEDGER}: Haskell's {@code failOnNonEmpty} records the failure
 *       and the transition continues into {@code LEDGER}, whose failures are appended.</li>
 * </ol>
 *
 * <p>{@code ConwayMempoolFailure} is a constructor of {@code ConwayLedgerPredFailure}
 * ({@code Ledger.hs:124}), so failures are reported under rule {@link LedgerRuleName#LEDGER}.</p>
 *
 * <p>This class is engine-neutral: the Java engine and the Amaru engine (ADR-057 §2, step 0) both
 * run it, so failure precedence is identical across engines.</p>
 */
public final class MempoolRule {

    /** Haskell {@code ConwayMempoolFailure}. */
    public static final String CONWAY_MEMPOOL_FAILURE = "ConwayMempoolFailure";

    /** Mempool.hs:118, verbatim. */
    public static final String ALL_INPUTS_SPENT =
            "All inputs are spent. Transaction has probably already been included";

    /** Mempool.hs:133, verbatim; followed by Haskell's {@code show} of the offending hot credentials. */
    public static final String UNELECTED_COMMITTEE_VOTERS_PREFIX =
            "Unelected committee members are not allowed to cast votes: ";

    /** Last protocol major version at which the MEMPOOL rule itself rejects unelected committee voters. */
    public static final int LAST_MEMPOOL_UNELECTED_CHECK_MAJOR = 10;

    /** Haskell {@code Ord (Credential r)}: {@code ScriptHashObj} before {@code KeyHashObj}, then the hash bytes. */
    private static final Comparator<CredentialKey> HASKELL_CREDENTIAL_ORDER =
            Comparator.comparing((CredentialKey c) -> c.type() == CredentialType.SCRIPT ? 0 : 1)
                    .thenComparing(CredentialKey::hashHex);

    private MempoolRule() {
    }

    /**
     * The outcome of the MEMPOOL checks.
     *
     * @param failures         the failures, in the order Haskell reports them; empty when both checks
     *                         pass
     * @param continueToLedger whether {@code LEDGER} runs after MEMPOOL ({@code false} only for the
     *                         all-inputs-spent short-circuit)
     */
    public record Result(List<LedgerFailure> failures, boolean continueToLedger) {

        private static final Result PASSED = new Result(List.of(), true);

        public Result {
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        }

        public boolean passed() {
            return failures.isEmpty();
        }
    }

    /**
     * Runs the MEMPOOL checks.
     *
     * @param body          the decoded transaction body
     * @param incoming      the state the transaction is admitted against (before any of its own
     *                      certificates)
     * @param protocolMajor the ticked protocol major version
     * @return the MEMPOOL result
     * @throws LedgerStateUnavailableException when a read needed for the verdict is unavailable
     */
    public static Result apply(TransactionBody body, LedgerView incoming, int protocolMajor) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(incoming, "incoming");

        if (!anySpendingInputUnspent(body, incoming)) {
            return new Result(List.of(failure(ALL_INPUTS_SPENT)), false);
        }

        if (protocolMajor <= LAST_MEMPOOL_UNELECTED_CHECK_MAJOR) {
            Set<CredentialKey> unelected = unelectedCommitteeVoters(body.getVotingProcedures(), incoming);
            if (!unelected.isEmpty()) {
                return new Result(List.of(failure(UNELECTED_COMMITTEE_VOTERS_PREFIX + showCredentials(unelected))),
                        true);
            }
        }
        return Result.PASSED;
    }

    /**
     * {@code any (`Map.member` utxo) inputs}. A present input decides the answer on its own, so an
     * unavailable read only matters when no input is known to be unspent.
     */
    private static boolean anySpendingInputUnspent(TransactionBody body, LedgerView incoming) {
        List<TransactionInput> inputs = body.getInputs() != null ? body.getInputs() : List.of();
        String unavailable = null;
        for (TransactionInput input : inputs) {
            Outpoint outpoint = Outpoints.of(input.getTransactionId(), input.getIndex());
            Lookup<?> lookup = incoming.utxo(outpoint);
            switch (lookup) {
                case Lookup.Present<?> p -> {
                    return true;
                }
                case Lookup.Unavailable<?> u -> unavailable = unavailable != null ? unavailable : u.reason();
                case Lookup.Absent<?> a -> {
                }
            }
        }
        if (unavailable != null) {
            throw new LedgerStateUnavailableException(unavailable);
        }
        return false;
    }

    /**
     * {@code unelectedCommitteeVoters}: the committee hot credentials among the voters that no
     * elected, non-resigned member has authorised, in Haskell {@code Set} order.
     */
    static Set<CredentialKey> unelectedCommitteeVoters(VotingProcedures votes, LedgerView incoming) {
        Set<CredentialKey> voters = new HashSet<>();
        if (votes != null && votes.getVoting() != null) {
            for (var voter : votes.getVoting().keySet()) {
                Voter v = Voter.of(voter);
                if (v.role() == Voter.Role.CONSTITUTIONAL_COMMITTEE) {
                    voters.add(v.credential());
                }
            }
        }
        if (voters.isEmpty()) {
            return Set.of();
        }
        Set<CredentialKey> authorizedElected = new HashSet<>();
        for (CommitteeMemberState member : incoming.committeeMembers().require("committee members")) {
            if (member.isElected() && !member.resigned() && member.hot() != null) {
                authorizedElected.add(member.hot());
            }
        }
        Set<CredentialKey> unelected = new TreeSet<>(HASKELL_CREDENTIAL_ORDER);
        for (CredentialKey hot : voters) {
            if (!authorizedElected.contains(hot)) {
                unelected.add(hot);
            }
        }
        return unelected;
    }

    /** Haskell {@code show} of a {@code [Credential HotCommitteeRole]}. */
    static String showCredentials(Set<CredentialKey> credentials) {
        List<String> shown = new ArrayList<>(credentials.size());
        for (CredentialKey credential : credentials) {
            shown.add(credential.type() == CredentialType.SCRIPT
                    ? "ScriptHashObj (ScriptHash \"" + credential.hashHex() + "\")"
                    : "KeyHashObj (KeyHash {unKeyHash = \"" + credential.hashHex() + "\"})");
        }
        return "[" + String.join(",", shown) + "]";
    }

    private static LedgerFailure failure(String text) {
        return new LedgerFailure(LedgerRuleName.LEDGER, CONWAY_MEMPOOL_FAILURE, LedgerFailure.Phase.PHASE_1, text);
    }
}
