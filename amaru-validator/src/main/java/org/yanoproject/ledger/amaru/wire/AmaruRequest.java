package org.yanoproject.ledger.amaru.wire;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.amaru.AmaruLedgerConstants;
import org.yanoproject.ledger.amaru.AmaruNetworkParameters;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepTarget;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * One v1 {@code validate} request (INTERFACE.md, {@code validate_request}), as plain data.
 * {@link AmaruRequestEncoder} writes it byte for byte as the Rust reference encoder
 * ({@code amaru-validator-wasm/src/interface.rs}, {@code Request::to_cbor}) does, slices in the order
 * given here.
 *
 * @param mode                the validation mode
 * @param transaction         the transaction bytes as received
 * @param network             magic, era history, global parameters
 * @param protocolParameters  Amaru's 31-element parameter layout ({@link ProtocolParamsEncoder})
 * @param ledgerConstants     test-only overrides; {@link AmaruLedgerConstants#HASKELL} omits key 7
 * @param dormantEpochs       consecutive dormant epochs
 * @param guardrailScriptHash the constitution's guardrail script hash, or null (key 9 omitted)
 * @param roots               enacted governance roots
 * @param treasury            the treasury
 * @param slot                the transaction pointer's slot
 * @param transactionIndex    the transaction pointer's index
 */
public record AmaruRequest(Mode mode, byte[] transaction, AmaruNetworkParameters network, byte[] protocolParameters,
                           AmaruLedgerConstants ledgerConstants, long dormantEpochs, String guardrailScriptHash,
                           EnactedRoots roots, BigInteger treasury, long slot, long transactionIndex,
                           List<Utxo> utxo, List<Account> accounts, List<PoolId> pools, List<DRep> dreps,
                           List<CommitteeMember> committee, List<Proposal> proposals) {

    public AmaruRequest {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(protocolParameters, "protocolParameters");
        ledgerConstants = ledgerConstants != null ? ledgerConstants : AmaruLedgerConstants.HASKELL;
        roots = roots != null ? roots : EnactedRoots.NONE;
        Objects.requireNonNull(treasury, "treasury");
        utxo = List.copyOf(utxo);
        accounts = List.copyOf(accounts);
        pools = List.copyOf(pools);
        dreps = List.copyOf(dreps);
        committee = List.copyOf(committee);
        proposals = List.copyOf(proposals);
    }

    /** INTERFACE.md {@code mode}. */
    public enum Mode {
        /** Amaru's phase-one rules only. */
        PHASE_ONE(0),
        /** Phase one, then phase two in Amaru's UPLC machine. */
        FULL(1);

        private final int wire;

        Mode(int wire) {
            this.wire = wire;
        }

        public int wire() {
            return wire;
        }
    }

    /** {@code cert_pointer}; a null pointer is sent as CBOR null ("not tracked by the host"). */
    public record CertPointer(long slot, long transactionIndex, long certificateIndex) {
    }

    /** {@code utxo_entry = [tx_in, bytes .cbor transaction_output]}. */
    public record Utxo(Outpoint outpoint, byte[] output) {
        public Utxo {
            Objects.requireNonNull(outpoint, "outpoint");
            Objects.requireNonNull(output, "output");
        }
    }

    /** {@code account}. Delegations and pointers may be null. */
    public record Account(CredentialKey credential, BigInteger deposit, BigInteger rewards, PoolId pool,
                          CertPointer poolPointer, DRepTarget drep, CertPointer drepPointer) {
        public Account {
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(deposit, "deposit");
            Objects.requireNonNull(rewards, "rewards");
        }
    }

    /** {@code drep_entry}. {@code registeredAt} may be null. */
    public record DRep(CredentialKey credential, BigInteger deposit, CertPointer registeredAt, long validUntil) {
        public DRep {
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(deposit, "deposit");
        }
    }

    /**
     * {@code committee_member}: status {@code [0, hot]} when {@code hot} is set, {@code [1]} when
     * resigned, otherwise null; {@code validUntil} null when the member holds no term.
     */
    public record CommitteeMember(CredentialKey cold, CredentialKey hot, boolean resigned, Long validUntil) {
        public CommitteeMember {
            Objects.requireNonNull(cold, "cold");
            if (resigned && hot != null) {
                throw new IllegalArgumentException("a resigned member has no hot credential");
            }
        }
    }

    /** {@code proposal = [gov_action_id, proposal_kind, valid_until]}. */
    public record Proposal(GovActionId id, ProposalKind kind, long validUntil) {
        public Proposal {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** {@code proposal_kind}. */
    public sealed interface ProposalKind {
        /** {@code [0, any_in_security_group]}. */
        record ParameterChange(boolean anyInSecurityGroup) implements ProposalKind {
        }

        /** {@code [1, major, minor]}. */
        record HardFork(long major, long minor) implements ProposalKind {
        }

        /** {@code [2]}: no confidence or update committee. */
        record Committee() implements ProposalKind {
        }

        /** {@code [3]}. */
        record Constitution() implements ProposalKind {
        }

        /** {@code [4]}. */
        record TreasuryWithdrawals() implements ProposalKind {
        }

        /** {@code [5]}. */
        record Info() implements ProposalKind {
        }
    }
}
