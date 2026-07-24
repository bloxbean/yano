package com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.zeroj.onchain.julc.groth16.lib.Groth16BLS12381Lib;

import java.math.BigInteger;

/**
 * Proof-gated validity-root thread.
 *
 * <p>Action 0 is permissionless proof advancement. Action 1 is an
 * authority-signed, root-preserving handoff to a precommitted successor
 * script; ordinary advancement can never alter the settlement identity.</p>
 */
@SpendingValidator
public final class EutxoValidityRootValidator {
    @Param
    static byte[] rootThreadPolicyId;

    @Param
    static byte[] rootThreadAssetName;

    @Param
    static BigInteger settlementContext;

    @Param
    static byte[] vkAlpha;

    @Param
    static byte[] vkBeta;

    @Param
    static byte[] vkGamma;

    @Param
    static byte[] vkDelta;

    @Param
    static PlutusData vkIc;

    @Param
    static byte[] migrationAuthority;

    @Param
    static byte[] migrationTargetScriptHash;

    record RootDatum(
            BigInteger version,
            byte[] chainId,
            BigInteger bridgeEpoch,
            BigInteger height,
            BigInteger validityRoot,
            BigInteger settlementContext,
            BigInteger batchDataCommitment,
            BigInteger withdrawalCommitment,
            BigInteger generation
    ) {
    }

    record Action(
            BigInteger kind,
            BigInteger previousRoot,
            BigInteger nextRoot,
            BigInteger transitionDigest,
            BigInteger ownerCommitment,
            BigInteger batchSize,
            BigInteger settlementContext,
            BigInteger batchDataCommitment,
            BigInteger withdrawalCommitment,
            byte[] piA,
            byte[] piB,
            byte[] piC
    ) {
    }

    private EutxoValidityRootValidator() {
    }

    @Entrypoint
    public static boolean validate(
            RootDatum current,
            Action action,
            ScriptContext context
    ) {
        if (!datumShapeValid(current)
                || !currentValid(current)) {
            return false;
        }
        if (action.kind().signum() == 0) {
            return advance(current, action, context);
        }
        return action.kind().equals(BigInteger.ONE)
                && migrate(current, action, context);
    }

    private static boolean advance(
            RootDatum current,
            Action action,
            ScriptContext context
    ) {
        JulcList<TxOut> continuing =
                ContextsLib.getContinuingOutputs(context);
        if (continuing.size() != 1
                || !hasThread(continuing.head())
                || !advanceOutputValid(
                continuing.head().datum(), current, action)) {
            return false;
        }
        return proofShapeValid(action)
                && Groth16BLS12381Lib.verify(
                publicInputs(action),
                action.piA(),
                action.piB(),
                action.piC(),
                vkAlpha,
                vkBeta,
                vkGamma,
                vkDelta,
                vkIc);
    }

    private static boolean migrate(
            RootDatum current,
            Action action,
            ScriptContext context
    ) {
        if (Builtins.lengthOfByteString(migrationAuthority) != 28
                || Builtins.lengthOfByteString(
                migrationTargetScriptHash) != 28
                || !ContextsLib.signedBy(
                context.txInfo(), migrationAuthority)) {
            return false;
        }
        JulcList<TxOut> targets = ContextsLib.scriptOutputsAt(
                context.txInfo(), migrationTargetScriptHash);
        return targets.size() == 1
                && hasThread(targets.head())
                && migrationOutputValid(
                targets.head().datum(), current, action);
    }

    static boolean currentValid(RootDatum current) {
        return current.version().equals(BigInteger.ONE)
                && Builtins.lengthOfByteString(current.chainId()) >= 1
                && Builtins.lengthOfByteString(current.chainId()) <= 128
                && current.bridgeEpoch().signum() >= 0
                && current.height().signum() >= 0
                && current.validityRoot().signum() >= 0
                && current.settlementContext().equals(settlementContext)
                && current.batchDataCommitment().signum() >= 0
                && current.withdrawalCommitment().signum() >= 0
                && current.generation().signum() >= 0;
    }

    static boolean datumShapeValid(RootDatum datum) {
        PlutusData fields = Builtins.sndPair(
                Builtins.unConstrData(datum));
        return Builtins.constrTag(datum) == 0
                && Builtins.nullList(Builtins.dropList(9, fields));
    }

    private static boolean proofShapeValid(Action action) {
        return action.previousRoot().signum() >= 0
                && action.nextRoot().signum() >= 0
                && action.transitionDigest().signum() >= 0
                && action.ownerCommitment().signum() >= 0
                && action.batchSize().compareTo(BigInteger.ONE) >= 0
                && action.batchSize().compareTo(BigInteger.valueOf(4)) <= 0
                && action.settlementContext().equals(settlementContext)
                && action.batchDataCommitment().signum() >= 0
                && action.withdrawalCommitment().signum() >= 0;
    }

    private static PlutusData publicInputs(Action action) {
        return Builtins.listData(Builtins.mkCons(
                Builtins.iData(action.previousRoot()),
                Builtins.mkCons(
                        Builtins.iData(action.nextRoot()),
                        Builtins.mkCons(
                                Builtins.iData(action.transitionDigest()),
                                Builtins.mkCons(
                                        Builtins.iData(action.ownerCommitment()),
                                        Builtins.mkCons(
                                                Builtins.iData(action.batchSize()),
                                                Builtins.mkCons(
                                                        Builtins.iData(
                                                                action.settlementContext()),
                                                        Builtins.mkCons(
                                                                Builtins.iData(
                                                                        action.batchDataCommitment()),
                                                                Builtins.mkCons(
                                                                        Builtins.iData(
                                                                                action.withdrawalCommitment()),
                                                                        Builtins.mkNilData())))))))));
    }

    private static boolean advanceOutputValid(
            OutputDatum outputDatum,
            RootDatum current,
            Action action
    ) {
        if (outputDatum
                instanceof OutputDatum.OutputDatumInline inline) {
            return nextDataValid(
                    inline.datum(), current, action, false);
        }
        return false;
    }

    private static boolean migrationOutputValid(
            OutputDatum outputDatum,
            RootDatum current,
            Action action
    ) {
        if (outputDatum
                instanceof OutputDatum.OutputDatumInline inline) {
            return nextDataValid(
                    inline.datum(), current, action, true);
        }
        return false;
    }

    private static boolean nextDataValid(
            PlutusData data,
            RootDatum current,
            Action action,
            boolean migration
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
        BigInteger root =
                Builtins.unIData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        BigInteger context =
                Builtins.unIData(Builtins.headList(f5));
        PlutusData f6 = Builtins.tailList(f5);
        BigInteger batch =
                Builtins.unIData(Builtins.headList(f6));
        PlutusData f7 = Builtins.tailList(f6);
        BigInteger withdrawal =
                Builtins.unIData(Builtins.headList(f7));
        PlutusData f8 = Builtins.tailList(f7);
        BigInteger generation =
                Builtins.unIData(Builtins.headList(f8));
        if (Builtins.constrTag(data) != 0
                || !Builtins.nullList(Builtins.tailList(f8))
                || !version.equals(BigInteger.ONE)
                || !Builtins.equalsByteString(
                chainId, current.chainId())) {
            return false;
        }
        if (migration) {
            return epoch.equals(
                    current.bridgeEpoch().add(BigInteger.ONE))
                    && height.equals(current.height())
                    && root.equals(current.validityRoot())
                    && context.signum() >= 0
                    && !context.equals(current.settlementContext())
                    && batch.signum() >= 0
                    && batch.equals(current.batchDataCommitment())
                    && withdrawal.signum() >= 0
                    && withdrawal.equals(
                    current.withdrawalCommitment())
                    && generation.equals(
                    current.generation().add(BigInteger.ONE));
        }
        return epoch.equals(current.bridgeEpoch())
                && height.compareTo(current.height()) > 0
                && root.equals(action.nextRoot())
                && action.previousRoot().equals(
                current.validityRoot())
                && context.equals(current.settlementContext())
                && context.equals(action.settlementContext())
                && batch.equals(action.batchDataCommitment())
                && withdrawal.equals(
                action.withdrawalCommitment())
                && generation.equals(current.generation());
    }

    private static boolean hasThread(TxOut output) {
        return ValuesLib.assetOf(
                output.value(),
                rootThreadPolicyId,
                rootThreadAssetName)
                .equals(BigInteger.ONE);
    }
}
