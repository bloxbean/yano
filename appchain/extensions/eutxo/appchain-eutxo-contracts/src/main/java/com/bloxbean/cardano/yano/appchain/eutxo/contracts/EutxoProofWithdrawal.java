package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.yano.appchain.proofs.MpfNormalizedProof;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Claim and normalized MPF proof submitted to the proof-gated L1 scripts. */
public record EutxoProofWithdrawal(
        int abiVersion,
        EutxoWithdrawalCommitment commitment,
        MpfNormalizedProof proof
) {
    public static final int ABI_VERSION = 1;

    public EutxoProofWithdrawal {
        if (abiVersion != ABI_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported proof withdrawal ABI");
        }
        Objects.requireNonNull(commitment, "commitment");
        Objects.requireNonNull(proof, "proof");
        if (!proof.verify()) {
            throw new IllegalArgumentException(
                    "proof withdrawal requires a valid normalized MPF proof");
        }
        String claimId = HexFormat.of().formatHex(commitment.claimId());
        if (!Arrays.equals(
                proof.key(),
                EutxoStateKeys.withdrawalCommitment(claimId))
                || !Arrays.equals(proof.value(), commitment.encode())) {
            throw new IllegalArgumentException(
                    "MPF proof does not commit to this withdrawal claim");
        }
    }

    public PlutusData toPlutusData() {
        List<PlutusData> encodedFolds = proof.folds().stream()
                .map(EutxoProofWithdrawal::foldData)
                .toList();
        return ConstrPlutusData.of(
                0,
                BigIntPlutusData.of(abiVersion),
                commitment.toPlutusData(),
                BytesPlutusData.of(proof.key()),
                BytesPlutusData.of(proof.value()),
                BytesPlutusData.of(proof.leafSuffix()),
                new ListPlutusData(encodedFolds, false));
    }

    public byte[] encode() {
        return toPlutusData().serializeToBytes();
    }

    private static PlutusData foldData(MpfNormalizedProof.FoldStep fold) {
        List<byte[]> neighbors = fold.neighbors();
        return ConstrPlutusData.of(
                0,
                BigIntPlutusData.of(fold.cursor()),
                BytesPlutusData.of(fold.prefix()),
                BigIntPlutusData.of(fold.nibble()),
                BytesPlutusData.of(neighbors.get(0)),
                BytesPlutusData.of(neighbors.get(1)),
                BytesPlutusData.of(neighbors.get(2)),
                BytesPlutusData.of(neighbors.get(3)),
                BytesPlutusData.of(fold.branchValueHash()));
    }
}
