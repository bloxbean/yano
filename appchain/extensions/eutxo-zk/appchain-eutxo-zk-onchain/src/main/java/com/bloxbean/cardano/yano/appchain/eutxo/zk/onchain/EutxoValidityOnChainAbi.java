package com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchManifest;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProof;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchVerificationKey;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkPublicInputs;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkSettlementPublicInputs;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkVerificationKey;
import com.bloxbean.cardano.zeroj.onchain.julc.groth16.codec.SnarkjsToCardano;

import java.util.List;
import java.util.Objects;

/** Canonical Plutus-data ABI for the EUTxO Groth16 validity verifier. */
public final class EutxoValidityOnChainAbi {
    private EutxoValidityOnChainAbi() {
    }

    public static PlutusData publicInputs(EutxoZkPublicInputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        return PlutusData.list(inputs.ordered().stream()
                .map(PlutusData::integer)
                .toArray(PlutusData[]::new));
    }

    public static PlutusData publicInputs(
            EutxoZkSettlementPublicInputs inputs
    ) {
        Objects.requireNonNull(inputs, "inputs");
        return PlutusData.list(inputs.ordered().stream()
                .map(PlutusData::integer)
                .toArray(PlutusData[]::new));
    }

    public static PlutusData proof(
            SnarkjsToCardano.ProofCompressed proof
    ) {
        Objects.requireNonNull(proof, "proof");
        return PlutusData.constr(
                0,
                PlutusData.bytes(proof.piA()),
                PlutusData.bytes(proof.piB()),
                PlutusData.bytes(proof.piC()));
    }

    public static PlutusData proof(EutxoZkProofArtifact proof) {
        Objects.requireNonNull(proof, "proof");
        return PlutusData.constr(
                0,
                PlutusData.bytes(proof.piA()),
                PlutusData.bytes(proof.piB()),
                PlutusData.bytes(proof.piC()));
    }

    public static List<PlutusData> verificationKeyParameters(
            SnarkjsToCardano.VkCompressed key
    ) {
        Objects.requireNonNull(key, "key");
        PlutusData ic = PlutusData.list(key.ic().stream()
                .map(PlutusData::bytes)
                .toArray(PlutusData[]::new));
        return List.of(
                PlutusData.bytes(key.alpha()),
                PlutusData.bytes(key.beta()),
                PlutusData.bytes(key.gamma()),
                PlutusData.bytes(key.delta()),
                ic);
    }

    public static List<PlutusData> verificationKeyParameters(
            EutxoZkVerificationKey key
    ) {
        Objects.requireNonNull(key, "key");
        PlutusData ic = PlutusData.list(key.ic().stream()
                .map(PlutusData::bytes)
                .toArray(PlutusData[]::new));
        return List.of(
                PlutusData.bytes(key.alpha()),
                PlutusData.bytes(key.beta()),
                PlutusData.bytes(key.gamma()),
                PlutusData.bytes(key.delta()),
                ic);
    }

    public static List<PlutusData> verificationKeyParameters(
            EutxoZkBatchVerificationKey key
    ) {
        Objects.requireNonNull(key, "key");
        PlutusData ic = PlutusData.list(key.ic().stream()
                .map(PlutusData::bytes)
                .toArray(PlutusData[]::new));
        return List.of(
                PlutusData.bytes(key.alpha()),
                PlutusData.bytes(key.beta()),
                PlutusData.bytes(key.gamma()),
                PlutusData.bytes(key.delta()),
                ic);
    }

    public static PlutusData rootDatum(
            String chainId,
            long bridgeEpoch,
            long height,
            EutxoZkSettlementPublicInputs inputs,
            long generation
    ) {
        Objects.requireNonNull(chainId, "chainId");
        Objects.requireNonNull(inputs, "inputs");
        return PlutusData.constr(
                0,
                PlutusData.integer(1),
                PlutusData.bytes(
                        chainId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                PlutusData.integer(bridgeEpoch),
                PlutusData.integer(height),
                PlutusData.integer(inputs.nextRoot()),
                PlutusData.integer(inputs.settlementContext()),
                PlutusData.integer(inputs.batchDataCommitment()),
                PlutusData.integer(inputs.withdrawalCommitment()),
                PlutusData.integer(generation));
    }

    public static PlutusData advanceRedeemer(
            EutxoZkProofArtifact proof,
            EutxoZkBatchData batchData
    ) {
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(batchData, "batchData");
        if (!java.util.Arrays.equals(
                batchData.commitment(),
                proof.statement().batchDataCommitment())
                || !batchData.commitmentScalar().equals(
                proof.statement().publicInputs()
                        .batchDataCommitment())) {
            throw new IllegalArgumentException(
                    "batch data does not match proof statement");
        }
        EutxoZkSettlementPublicInputs inputs =
                proof.statement().publicInputs();
        return PlutusData.constr(
                0,
                PlutusData.integer(0),
                PlutusData.integer(inputs.previousRoot()),
                PlutusData.integer(inputs.nextRoot()),
                PlutusData.integer(inputs.transitionDigest()),
                PlutusData.integer(inputs.ownerCommitment()),
                PlutusData.integer(inputs.batchSize()),
                PlutusData.integer(inputs.settlementContext()),
                PlutusData.integer(inputs.batchDataCommitment()),
                PlutusData.integer(inputs.withdrawalCommitment()),
                PlutusData.bytes(batchData.canonicalBytes()),
                PlutusData.bytes(proof.piA()),
                PlutusData.bytes(proof.piB()),
                PlutusData.bytes(proof.piC()));
    }

    public static PlutusData advanceRedeemer(
            EutxoZkBatchProof proof,
            EutxoZkBatchManifest manifest
    ) {
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(manifest, "manifest");
        EutxoZkSettlementPublicInputs inputs =
                proof.settlementInputs();
        if (!manifest.transactionIds().equals(
                proof.transactionIds())
                || !manifest.commitmentScalar().equals(
                inputs.batchDataCommitment())) {
            throw new IllegalArgumentException(
                    "batch manifest does not match proof statement");
        }
        return PlutusData.constr(
                0,
                PlutusData.integer(0),
                PlutusData.integer(inputs.previousRoot()),
                PlutusData.integer(inputs.nextRoot()),
                PlutusData.integer(inputs.transitionDigest()),
                PlutusData.integer(inputs.ownerCommitment()),
                PlutusData.integer(inputs.batchSize()),
                PlutusData.integer(inputs.settlementContext()),
                PlutusData.integer(inputs.batchDataCommitment()),
                PlutusData.integer(inputs.withdrawalCommitment()),
                PlutusData.bytes(manifest.canonicalBytes()),
                PlutusData.bytes(proof.piA()),
                PlutusData.bytes(proof.piB()),
                PlutusData.bytes(proof.piC()));
    }
}
