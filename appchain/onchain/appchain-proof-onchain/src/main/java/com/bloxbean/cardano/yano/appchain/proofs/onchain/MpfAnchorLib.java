package com.bloxbean.cardano.yano.appchain.proofs.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/** Shared validation of the unique SCRIPT anchor reference input and its commitment identity. */
@OnchainLibrary
public final class MpfAnchorLib {
    public record AnchorRoot(byte[] stateRoot) { }

    private MpfAnchorLib() { }

    public static Optional<AnchorRoot> acceptedAnchor(
            ScriptContext context, byte[] anchorThreadPolicyId,
            byte[] anchorThreadAssetName, byte[] anchorScriptHash,
            byte[] expectedChainGenesisId, byte[] expectedApplicationId,
            byte[] expectedCommitmentProfileId, byte[] expectedFormatFingerprint) {
        Optional<AnchorRoot> result = Optional.empty();
        BigInteger count = BigInteger.ZERO;
        for (TxInInfo input : context.txInfo().referenceInputs()) {
            if (ValuesLib.assetOf(input.resolved().value(), anchorThreadPolicyId,
                    anchorThreadAssetName).equals(BigInteger.ONE)
                    && atScriptAddress(input.resolved(), anchorScriptHash)) {
                count = count.add(BigInteger.ONE);
                result = anchor(input.resolved().datum(), expectedChainGenesisId,
                        expectedApplicationId, expectedCommitmentProfileId,
                        expectedFormatFingerprint);
            }
        }
        return count.equals(BigInteger.ONE) ? result : Optional.empty();
    }

    private static Optional<AnchorRoot> anchor(
            OutputDatum datum, byte[] expectedChainGenesisId,
            byte[] expectedApplicationId, byte[] expectedCommitmentProfileId,
            byte[] expectedFormatFingerprint) {
        if (datum instanceof OutputDatum.OutputDatumInline inline) {
            return anchorData(inline.datum(), expectedChainGenesisId,
                    expectedApplicationId, expectedCommitmentProfileId,
                    expectedFormatFingerprint);
        }
        return Optional.empty();
    }

    private static Optional<AnchorRoot> anchorData(
            PlutusData value, byte[] expectedChainGenesisId,
            byte[] expectedApplicationId, byte[] expectedCommitmentProfileId,
            byte[] expectedFormatFingerprint) {
        if (Builtins.constrTag(value) != 0) return Optional.empty();
        PlutusData fields = Builtins.constrFields(value);
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chainId = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        byte[] genesis = Builtins.unBData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        byte[] application = Builtins.unBData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] profile = Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        byte[] fingerprint = Builtins.unBData(Builtins.headList(f5));
        PlutusData f6 = Builtins.tailList(f5);
        BigInteger height = Builtins.unIData(Builtins.headList(f6));
        PlutusData f7 = Builtins.tailList(f6);
        byte[] blockHash = Builtins.unBData(Builtins.headList(f7));
        PlutusData f8 = Builtins.tailList(f7);
        byte[] stateRoot = Builtins.unBData(Builtins.headList(f8));
        PlutusData f9 = Builtins.tailList(f8);
        PlutusData f10 = Builtins.tailList(f9);
        PlutusData trailing = Builtins.tailList(f10);
        if (!version.equals(BigInteger.ONE)
                || !Builtins.nullList(trailing)
                || Builtins.lengthOfByteString(chainId) < 1
                || Builtins.lengthOfByteString(chainId) > 128
                || height.signum() < 0
                || Builtins.lengthOfByteString(blockHash) != 32
                || Builtins.lengthOfByteString(stateRoot) != 32
                || !Builtins.equalsByteString(genesis, expectedChainGenesisId)
                || !Builtins.equalsByteString(application, expectedApplicationId)
                || !Builtins.equalsByteString(profile, expectedCommitmentProfileId)
                || !Builtins.equalsByteString(fingerprint, expectedFormatFingerprint)) {
            return Optional.empty();
        }
        return Optional.of(new AnchorRoot(stateRoot));
    }

    private static boolean atScriptAddress(TxOut output, byte[] scriptHash) {
        PlutusData credential = output.address().credential().toPlutusData();
        PlutusData fields = Builtins.constrFields(credential);
        byte[] observed = Builtins.unBData(Builtins.headList(fields));
        return Builtins.constrTag(credential) == 1
                && Builtins.nullList(Builtins.tailList(fields))
                && Builtins.equalsByteString(observed, scriptHash);
    }
}
