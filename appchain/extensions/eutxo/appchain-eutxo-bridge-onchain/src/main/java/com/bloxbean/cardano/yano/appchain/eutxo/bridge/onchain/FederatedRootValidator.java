package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;

/** Threshold-controlled, monotonically advancing app-chain MPF root. */
@SpendingValidator
public final class FederatedRootValidator {
    @Param
    static byte[] rootThreadPolicyId;

    @Param
    static byte[] rootThreadAssetName;

    record RootDatum(
            BigInteger version,
            byte[] chainId,
            BigInteger bridgeEpoch,
            BigInteger height,
            byte[] stateRoot,
            JulcList<byte[]> memberKeys,
            BigInteger threshold,
            BigInteger generation
    ) {
    }

    private FederatedRootValidator() {
    }

    @Entrypoint
    public static boolean validate(
            RootDatum current,
            PlutusData redeemer,
            ScriptContext context
    ) {
        BigInteger action = Builtins.unIData(redeemer);
        if (!datumShapeValid(current)
                || !baseProfileValid(
                current.version(),
                current.chainId(),
                current.bridgeEpoch(),
                current.height(),
                current.stateRoot(),
                current.generation())
                || !currentValidAndSigned(
                context.txInfo(),
                current.memberKeys(),
                current.threshold())) {
            return false;
        }
        JulcList<TxOut> continuing =
                ContextsLib.getContinuingOutputs(context);
        return continuing.size() == 1
                && hasThread(continuing.head())
                && inlineNextStateValid(
                continuing.head().datum(), current, action);
    }

    static boolean datumShapeValid(RootDatum datum) {
        PlutusData fields = Builtins.sndPair(
                Builtins.unConstrData(datum));
        PlutusData trailing = Builtins.dropList(8, fields);
        return Builtins.constrTag(datum) == 0
                && Builtins.nullList(trailing);
    }

    static boolean baseProfileValid(
            BigInteger version,
            byte[] chainId,
            BigInteger bridgeEpoch,
            BigInteger height,
            byte[] stateRoot,
            BigInteger generation
    ) {
        long chainLength = Builtins.lengthOfByteString(chainId);
        return version.equals(BigInteger.ONE)
                && chainLength >= 1 && chainLength <= 128
                && bridgeEpoch.signum() >= 0
                && height.signum() >= 0
                && Builtins.lengthOfByteString(stateRoot) == 32
                && generation.signum() >= 0;
    }

    static boolean currentValidAndSigned(
            TxInfo txInfo,
            JulcList<byte[]> memberKeys,
            BigInteger threshold
    ) {
        if (memberKeys.size() < 1 || memberKeys.size() > 32) {
            return false;
        }
        BigInteger count = BigInteger.ZERO;
        BigInteger signerCount = BigInteger.ZERO;
        byte[] previous = Builtins.emptyByteString();
        boolean valid = true;
        for (byte[] memberKey : memberKeys) {
            if (Builtins.lengthOfByteString(memberKey) != 32
                    || (count.compareTo(BigInteger.ZERO) > 0
                    && !Builtins.lessThanByteString(previous, memberKey))) {
                valid = false;
                break;
            }
            previous = memberKey;
            count = count.add(BigInteger.ONE);
            byte[] keyHash = Builtins.blake2b_224(memberKey);
            if (ContextsLib.signedBy(txInfo, keyHash)) {
                signerCount = signerCount.add(BigInteger.ONE);
            }
        }
        return valid && count.compareTo(BigInteger.ONE) >= 0
                && threshold.compareTo(BigInteger.ONE) >= 0
                && threshold.compareTo(count) <= 0
                && signerCount.compareTo(threshold) >= 0;
    }

    private static boolean inlineNextStateValid(
            OutputDatum outputDatum,
            RootDatum current,
            BigInteger action
    ) {
        if (outputDatum
                instanceof OutputDatum.OutputDatumInline inline) {
            return nextDataValid(
                    inline.datum(), current, action);
        }
        return false;
    }

    private static boolean nextDataValid(
            PlutusData data,
            RootDatum current,
            BigInteger action
    ) {
        PlutusData fields = Builtins.sndPair(
                Builtins.unConstrData(data));
        BigInteger version =
                Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chainId =
                Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger epoch =
                Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        BigInteger height =
                Builtins.unIData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] stateRoot =
                Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        PlutusData members =
                Builtins.unListData(Builtins.headList(f5));
        PlutusData f6 = Builtins.tailList(f5);
        BigInteger threshold =
                Builtins.unIData(Builtins.headList(f6));
        PlutusData f7 = Builtins.tailList(f6);
        BigInteger generation =
                Builtins.unIData(Builtins.headList(f7));
        PlutusData trailing = Builtins.tailList(f7);
        if (Builtins.constrTag(data) != 0
                || !Builtins.nullList(trailing)
                || !baseProfileValid(
                version, chainId, epoch, height,
                stateRoot, generation)
                || !memberProfileValid(members, threshold)
                || !Builtins.equalsByteString(
                chainId, current.chainId())) {
            return false;
        }
        if (action.signum() == 0) {
            return epoch.equals(current.bridgeEpoch())
                    && generation.equals(current.generation())
                    && threshold.equals(current.threshold())
                    && Builtins.equalsByteString(
                    membersDigest(members),
                    membersDigest(current.memberKeys()))
                    && height.compareTo(current.height()) > 0;
        }
        return action.equals(BigInteger.ONE)
                && epoch.equals(
                current.bridgeEpoch().add(BigInteger.ONE))
                && generation.equals(
                current.generation().add(BigInteger.ONE));
    }

    static boolean memberProfileValid(
            PlutusData members,
            BigInteger threshold
    ) {
        BigInteger count = BigInteger.ZERO;
        byte[] previous = Builtins.emptyByteString();
        PlutusData remaining = members;
        boolean valid = true;
        while (!Builtins.nullList(remaining)) {
            byte[] member =
                    Builtins.unBData(Builtins.headList(remaining));
            if (count.compareTo(BigInteger.valueOf(32)) >= 0
                    || Builtins.lengthOfByteString(member) != 32
                    || (count.signum() > 0
                    && !Builtins.lessThanByteString(
                    previous, member))) {
                valid = false;
                break;
            }
            previous = member;
            count = count.add(BigInteger.ONE);
            remaining = Builtins.tailList(remaining);
        }
        return valid
                && count.signum() > 0
                && threshold.compareTo(BigInteger.ONE) >= 0
                && threshold.compareTo(count) <= 0;
    }

    private static byte[] membersDigest(PlutusData members) {
        byte[] encoded = Builtins.emptyByteString();
        PlutusData remaining = members;
        while (!Builtins.nullList(remaining)) {
            encoded = Builtins.appendByteString(
                    encoded,
                    Builtins.unBData(Builtins.headList(remaining)));
            remaining = Builtins.tailList(remaining);
        }
        return Builtins.blake2b_256(encoded);
    }

    private static byte[] membersDigest(JulcList<byte[]> members) {
        byte[] encoded = Builtins.emptyByteString();
        for (byte[] member : members) {
            encoded = Builtins.appendByteString(encoded, member);
        }
        return Builtins.blake2b_256(encoded);
    }

    private static boolean hasThread(TxOut output) {
        return ValuesLib.assetOf(
                output.value(),
                rootThreadPolicyId,
                rootThreadAssetName)
                .equals(BigInteger.ONE);
    }
}
