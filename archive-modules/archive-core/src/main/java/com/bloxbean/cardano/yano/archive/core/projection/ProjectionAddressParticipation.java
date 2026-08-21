package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.archive.ConsumedOutputAddresses;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource;
import com.bloxbean.cardano.yano.archive.core.address.AddressKeyCodec;
import com.bloxbean.cardano.yano.archive.core.dataset.AddressParticipationFact;
import com.bloxbean.cardano.yano.archive.core.dataset.AddressSubjectRows;
import com.bloxbean.cardano.yano.archive.core.dataset.AddressTransactionDataset;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolves a block's address participations at capture time.
 *
 * <p>Consumed inputs are the only part that needs live state: the output being spent was
 * created by an earlier block and is gone from the UTXO set by the time any sink reads the
 * envelope. It is resolved here, from addresses the UTXO subsystem captured while deleting
 * those outputs — the same reasoning that puts pointer-address resolution in canonical apply.
 *
 * <p>Outputs are parsed here too, with the <em>same</em> parser the live path uses, so the two
 * cannot disagree about what an address decomposes into.
 *
 * <p>Validity follows the existing rule exactly: a valid transaction contributes its inputs and
 * outputs, an invalid one contributes its collateral inputs and collateral return.
 */
final class ProjectionAddressParticipation {

    private ProjectionAddressParticipation() {}

    /**
     * @param consumed addresses of the outputs this block spent, captured during apply
     * @throws IllegalStateException if an input cannot be resolved — a silent skip would drop
     *                               address history for a real spend, which no later pass could
     *                               detect, so this fails closed
     */
    static AddressParticipationFact resolve(Block block, long blockSlot,
                                            ConsumedOutputAddresses consumed,
                                            AddressKeyCodec addressKeys,
                                            PointerCredentialSource pointerSource) {
        AddressTransactionDataset.PointerLookup pointers = coordinate -> {
            var credential = ProjectionPointerResolution.resolveCoordinate(
                    new PointerCredentialSource.PointerCoordinate(coordinate.slot(),
                            coordinate.txIndex(), coordinate.certIndex()),
                    blockSlot, pointerSource);
            return credential == null ? null
                    : new com.bloxbean.cardano.yano.archive.core.address.SequentialPointerResolver
                            .ResolvedStakeCredential(
                            ProjectionPointerResolution.credentialTypeNameOf(credential),
                            ProjectionPointerResolution.credentialHashOf(credential));
        };
        List<TransactionBody> transactions = block.getTransactionBodies() == null
                ? List.of() : block.getTransactionBodies();
        Set<Integer> invalid = block.getInvalidTransactions() == null
                ? Set.of() : Set.copyOf(block.getInvalidTransactions());
        int era = block.getEra() == null ? Era.Conway.getValue() : block.getEra().getValue();

        List<AddressParticipationFact.Transaction> result = new ArrayList<>(transactions.size());
        for (int txIndex = 0; txIndex < transactions.size(); txIndex++) {
            TransactionBody tx = transactions.get(txIndex);
            boolean valid = !invalid.contains(txIndex);
            List<AddressParticipationFact.Participation> participations = new ArrayList<>();

            var spent = valid ? tx.getInputs() : tx.getCollateralInputs();
            if (spent != null) {
                for (var input : spent) {
                    String address = consumed.addressOf(input.getTransactionId(), input.getIndex());
                    if (address == null) {
                        throw new IllegalStateException("address-transaction projection could not resolve"
                                + " consumed output " + input.getTransactionId() + '#' + input.getIndex()
                                + "; the address of a spent output must be captured during apply");
                    }
                    participations.add(new AddressParticipationFact.Participation(
                            (valid ? AddressSubjectRows.Role.INPUT
                                    : AddressSubjectRows.Role.COLLATERAL_INPUT).name(),
                            participant(address, era, addressKeys, pointers)));
                }
            }

            if (valid && tx.getOutputs() != null) {
                for (TransactionOutput output : tx.getOutputs()) {
                    participations.add(new AddressParticipationFact.Participation(
                            AddressSubjectRows.Role.OUTPUT.name(),
                            participant(output.getAddress(), era, addressKeys, pointers)));
                }
            } else if (!valid && tx.getCollateralReturn() != null) {
                participations.add(new AddressParticipationFact.Participation(
                        AddressSubjectRows.Role.COLLATERAL_RETURN.name(),
                        participant(tx.getCollateralReturn().getAddress(), era, addressKeys, pointers)));
            }

            result.add(new AddressParticipationFact.Transaction(
                    HexUtil.decodeHexString(tx.getTxHash()), txIndex, participations));
        }
        return new AddressParticipationFact(result);
    }

    private static AddressSubjectRows.Participant participant(String address, int era,
                                                              AddressKeyCodec addressKeys,
                                                              AddressTransactionDataset.PointerLookup pointers) {
        var parts = AddressTransactionDataset.parse(address, era, addressKeys, pointers);
        return new AddressSubjectRows.Participant(parts.addressKey(), parts.displayAddress(),
                parts.paymentCredential(), parts.stakeCredentialType(), parts.stakeCredential());
    }
}
