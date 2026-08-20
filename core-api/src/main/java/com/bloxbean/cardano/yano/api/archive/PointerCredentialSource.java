package com.bloxbean.cardano.yano.api.archive;

import java.util.Objects;
import java.util.Optional;

/**
 * Authoritative, <em>as-of</em> pointer-address resolution, owned by the ledger/account-state
 * contributor (ADR-039 open question 1).
 *
 * <p>A pre-Conway pointer address encodes the coordinate of a stake registration certificate
 * rather than a credential hash. The Shelley ledger keeps those coordinates in the delegation
 * state's {@code ptrs} map and <strong>removes a credential's entries when it is
 * deregistered</strong>, so a pointer whose target has been deregistered resolves to no stake
 * credential. Resolution therefore cannot be a simple registration lookup: it has to be
 * evaluated as of a position in the chain.
 *
 * <p>The interval that decides it is:
 *
 * <pre>
 * registration coordinate  &lt;  deregistration coordinate  &lt;=  projected block-end coordinate
 * </pre>
 *
 * <p>Everything is expressed in <em>coordinates</em>, never bare slots. Two deregistrations of
 * one credential can share a slot in different transactions, and a slot-only comparison would
 * conflate them and make same-slot ordering ambiguous.
 *
 * <p><strong>Replay determinism.</strong> Both the registration mapping and the deregistration
 * index are append-only and coordinate-keyed, and every query is bounded above by the block
 * being projected. Projecting block N therefore yields the same answer whether the store is at
 * N or at N+100: later events lie outside the interval by construction. This is what lets
 * ADR-039 contributor replay reproduce byte-identical sections.
 */
public interface PointerCredentialSource {

    /** A certificate's position in the chain: slot, then transaction, then certificate. */
    record PointerCoordinate(long slot, int txIndex, int certIndex)
            implements Comparable<PointerCoordinate> {
        public PointerCoordinate {
            if (slot < 0 || txIndex < 0 || certIndex < 0) {
                throw new IllegalArgumentException("coordinate components must not be negative");
            }
        }

        /** Upper bound covering every certificate in a block. */
        public static PointerCoordinate endOfBlock(long slot) {
            return new PointerCoordinate(slot, 0xFFFF, 0xFFFF);
        }

        @Override
        public int compareTo(PointerCoordinate other) {
            int bySlot = Long.compare(slot, other.slot);
            if (bySlot != 0) return bySlot;
            int byTx = Integer.compare(txIndex, other.txIndex);
            return byTx != 0 ? byTx : Integer.compare(certIndex, other.certIndex);
        }
    }

    /** A stake credential a pointer coordinate refers to. */
    record PointerCredential(int credentialType, String credentialHash) {
        public PointerCredential {
            credentialHash = Objects.requireNonNull(credentialHash, "credentialHash").toLowerCase();
        }
    }

    /** Whether the derived as-of index can be trusted for the pre-Conway range. */
    enum IndexCompleteness {
        /** Maintained from genesis; safe for projection history. */
        COMPLETE,
        /** Not maintained from genesis, so a pre-Conway deregistration may be missing. */
        INCOMPLETE,
        /** Pointer history was cleaned up after all safety gates passed; no longer queryable. */
        CLEANED
    }

    /** The credential registered at this coordinate, or empty when none was. */
    Optional<PointerCredential> registrationAt(PointerCoordinate coordinate);

    /**
     * Whether {@code credential} was deregistered strictly after {@code after} and at or before
     * {@code through}. Both bounds are coordinates, so same-slot ordering is exact.
     */
    boolean deregisteredWithin(PointerCredential credential, PointerCoordinate after,
                               PointerCoordinate through);

    /**
     * Completeness of the derived index. Projection history must fail closed on anything other
     * than {@link IndexCompleteness#COMPLETE}, rather than silently resolving pointers against a
     * partially populated index.
     */
    IndexCompleteness completeness();

    /** Resolves nothing and reports itself incomplete, so callers fail closed rather than guess. */
    PointerCredentialSource NONE = new PointerCredentialSource() {
        @Override public Optional<PointerCredential> registrationAt(PointerCoordinate coordinate) {
            return Optional.empty();
        }
        @Override public boolean deregisteredWithin(PointerCredential credential, PointerCoordinate after,
                                                    PointerCoordinate through) {
            return false;
        }
        @Override public IndexCompleteness completeness() {
            return IndexCompleteness.INCOMPLETE;
        }
    };
}
