package com.bloxbean.cardano.yano.appchain.eutxo.zk.prover;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoKeyPaymentSettlementCircuit;

import java.util.Objects;

/**
 * Phase C development boundary from a manually assembled amount tuple to one
 * durable job.
 *
 * <p>This is deliberately not a live runtime adapter. The preview ingestion
 * path consumes {@code EutxoFinalizedProofWitness} values derived from exact
 * finalized Cardano transactions. Keeping the old feasibility path visibly
 * named prevents an amount tuple from being mistaken for the accepted runtime
 * transition.</p>
 */
public final class EutxoDevelopmentBatchIngestor {
    private final EutxoProverService prover;

    public EutxoDevelopmentBatchIngestor(EutxoProverService prover) {
        this.prover = Objects.requireNonNull(prover, "prover");
    }

    public EutxoProverJob ingest(
            String chainId,
            long finalizedHeight,
            long bridgeEpoch,
            byte[] previousValidityRoot,
            EutxoKeyPaymentBatch witness
    ) {
        Objects.requireNonNull(previousValidityRoot, "previousValidityRoot");
        Objects.requireNonNull(witness, "witness");
        var batchData = new EutxoZkBatchData(witness.payments());
        var inputs = EutxoKeyPaymentSettlementCircuit.publicInputs(
                chainId,
                bridgeEpoch,
                prover.verificationKey().digestHex(),
                previousValidityRoot,
                witness,
                batchData.commitment(),
                EutxoKeyPaymentSettlementCircuit
                        .withdrawalCommitment(witness));
        var statement = new EutxoZkStatement(
                chainId,
                finalizedHeight,
                bridgeEpoch,
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT,
                inputs,
                batchData.commitment());
        return prover.submit(statement, batchData, witness);
    }
}
