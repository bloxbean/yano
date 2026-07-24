package com.bloxbean.cardano.yano.appchain.eutxo.zk.prover;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoKeyPaymentBatchCircuit;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoKeyPaymentSettlementCircuit;

import java.util.Objects;

/**
 * Deterministic boundary from a finalized bounded batch to one durable job.
 *
 * <p>The runtime adapter calls this only after finality. Replaying the same
 * finalized batch is idempotent because the statement digest is the job ID.</p>
 */
public final class EutxoFinalizedBatchIngestor {
    private final EutxoProverService prover;

    public EutxoFinalizedBatchIngestor(EutxoProverService prover) {
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
        var batchInputs = EutxoKeyPaymentBatchCircuit.publicInputs(
                previousValidityRoot, witness);
        var batchData = new EutxoZkBatchData(
                witness.payments(), batchInputs.ownerCommitment());
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
