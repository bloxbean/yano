package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/** Refundable staging contract for one accepted-vault or depositor-refund spend. */
@SpendingValidator
public final class DepositStagingValidator {
    private static final BigInteger ACCEPT = BigInteger.ZERO;
    private static final BigInteger REFUND = BigInteger.ONE;

    @Param
    static byte[] vaultScriptHash;

    record StagingDatum(
            BigInteger version,
            byte[] chainId,
            byte[] l2Owner,
            byte[] nonce,
            byte[] stagingTransactionId,
            BigInteger stagingIndex,
            byte[] depositorKeyHash,
            BigInteger refundDeadline
    ) {
    }

    private DepositStagingValidator() {
    }

    @Entrypoint
    public static boolean validate(
            StagingDatum datum,
            BigInteger action,
            ScriptContext context
    ) {
        if (!shapeValid(datum)) {
            return false;
        }
        if (action.equals(ACCEPT)) {
            return acceptsToVaultBeforeDeadline(datum, context);
        }
        if (action.equals(REFUND)) {
            return refundsToDepositorAfterDeadline(datum, context);
        }
        return false;
    }

    private static boolean acceptsToVaultBeforeDeadline(
            StagingDatum datum,
            ScriptContext context
    ) {
        BigInteger upper = IntervalLib.finiteUpperBound(context.txInfo().validRange());
        var outputs = ContextsLib.scriptOutputsAt(context.txInfo(), vaultScriptHash);
        if (upper.compareTo(datum.refundDeadline()) >= 0 || outputs.size() != 1) {
            return false;
        }
        TxOut accepted = outputs.head();
        return ValuesLib.lovelaceOf(accepted.value())
                .compareTo(ownInputLovelace(context)) >= 0
                && acceptedDatumMatches(accepted.datum(), datum);
    }

    private static boolean refundsToDepositorAfterDeadline(
            StagingDatum datum,
            ScriptContext context
    ) {
        BigInteger lower = IntervalLib.finiteLowerBound(context.txInfo().validRange());
        return lower.compareTo(datum.refundDeadline()) >= 0
                && ContextsLib.signedBy(context.txInfo(), datum.depositorKeyHash());
    }

    private static BigInteger ownInputLovelace(ScriptContext context) {
        Optional<TxInInfo> ownInput = ContextsLib.findOwnInput(context);
        return ValuesLib.lovelaceOf(ownInput.get().resolved().value());
    }

    static boolean shapeValid(StagingDatum datum) {
        return datum.version().equals(BigInteger.ONE)
                && datum.chainId().length >= 1 && datum.chainId().length <= 128
                && datum.l2Owner().length >= 1 && datum.l2Owner().length <= 256
                && datum.nonce().length == 32
                && datum.stagingTransactionId().length == 32
                && datum.stagingIndex().signum() >= 0
                && datum.depositorKeyHash().length == 28
                && datum.refundDeadline().signum() >= 0;
    }

    /**
     * The federation cannot change the intended L2 owner or replay another
     * staging output's datum while moving value into the accepted vault.
     */
    static boolean acceptedDatumMatches(OutputDatum outputDatum, StagingDatum staging) {
        if (outputDatum instanceof OutputDatum.OutputDatumInline inlineDatum) {
            return acceptedInlineDatumMatches(inlineDatum.datum(), staging);
        }
        return false;
    }

    private static boolean acceptedInlineDatumMatches(
            PlutusData datum,
            StagingDatum staging
    ) {
        PlutusData fields = Builtins.constrFields(datum);
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chainId = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        byte[] owner = Builtins.unBData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        byte[] nonce = Builtins.unBData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] transactionId = Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        BigInteger outputIndex = Builtins.unIData(Builtins.headList(f5));
        PlutusData f6 = Builtins.tailList(f5);
        BigInteger deadline = Builtins.unIData(Builtins.headList(f6));
        PlutusData trailing = Builtins.tailList(f6);

        return Builtins.constrTag(datum) == 0
                && Builtins.nullList(trailing)
                && version.equals(staging.version())
                && Builtins.equalsByteString(chainId, staging.chainId())
                && Builtins.equalsByteString(owner, staging.l2Owner())
                && Builtins.equalsByteString(nonce, staging.nonce())
                && Builtins.equalsByteString(transactionId, staging.stagingTransactionId())
                && outputIndex.equals(staging.stagingIndex())
                && deadline.equals(staging.refundDeadline());
    }
}
