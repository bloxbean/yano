package com.bloxbean.cardano.yano.archive.core.dataset;

import java.util.List;

/**
 * One block's address participations, already resolved.
 *
 * <p>Carried in the ADR-039 {@code address-transaction} section. Everything a sink needs to
 * emit {@code address_transactions} rows is here, because the sink has neither the block nor
 * the UTXO set: consumed inputs were resolved at capture time, and outputs were parsed from the
 * block then rather than re-parsed later.
 *
 * @param transactions per-transaction participations, in block order
 */
public record AddressParticipationFact(List<Transaction> transactions) {

    public AddressParticipationFact {
        transactions = List.copyOf(transactions);
    }

    /**
     * @param txHash        transaction id
     * @param txIndex       index within the block
     * @param participations every address that took part, with the role it played, in the order
     *                      the archive first encountered it
     */
    public record Transaction(byte[] txHash, int txIndex, List<Participation> participations) {
        public Transaction {
            participations = List.copyOf(participations);
        }
    }

    /**
     * @param role        {@code INPUT}, {@code OUTPUT}, {@code COLLATERAL_INPUT} or
     *                    {@code COLLATERAL_RETURN}, as the wire name of
     *                    {@link AddressSubjectRows.Role}
     * @param participant the resolved address parts
     */
    public record Participation(String role, AddressSubjectRows.Participant participant) { }
}
