package com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkPublicInputs;
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
}
