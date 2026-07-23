package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Federated vault spend guard. The parameter is the reviewed external
 * settlement service's Cardano payment key hash. The service is responsible
 * for threshold/HSM policy; the script additionally requires one claim-bound
 * continuing vault output and exact lovelace conservation.
 */
@SpendingValidator
public final class VaultValidator {
    @Param
    static byte[] settlementSignerKeyHash;

    private VaultValidator() {
    }

    @Entrypoint
    public static boolean validate(
            PlutusData datum,
            PlutusData redeemer,
            ScriptContext context
    ) {
        if (!ContextsLib.signedBy(context.txInfo(), settlementSignerKeyHash)) {
            return false;
        }
        var ownInput = ContextsLib.findOwnInput(context);
        var continuing = ContextsLib.getContinuingOutputs(context);
        if (ownInput.isEmpty() || continuing.size() != 1) {
            return false;
        }
        TxOut output = continuing.head();
        Optional<Settlement> parsed = settlement(output.datum());
        if (parsed.isEmpty()) {
            return false;
        }
        Settlement settlement = parsed.get();
        if (!Builtins.equalsByteString(settlement.claimId(), Builtins.unBData(redeemer))) {
            return false;
        }
        BigInteger inputLovelace =
                ValuesLib.lovelaceOf(ownInput.get().resolved().value());
        BigInteger outputLovelace = ValuesLib.lovelaceOf(output.value());
        return outputLovelace.signum() > 0
                && inputLovelace.subtract(outputLovelace)
                .equals(settlement.lovelace().add(context.txInfo().fee()));
    }

    static Optional<Settlement> settlement(OutputDatum datum) {
        if (datum instanceof OutputDatum.OutputDatumInline inline) {
            return settlement(inline.datum());
        }
        return Optional.empty();
    }

    private static Optional<Settlement> settlement(PlutusData value) {
        PlutusData fields = Builtins.constrFields(value);
        BigInteger abi = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chainId = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger bridgeEpoch = Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        byte[] claimId = Builtins.unBData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] destination = Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        BigInteger lovelace = Builtins.unIData(Builtins.headList(f5));
        PlutusData trailing = Builtins.tailList(f5);
        if (Builtins.constrTag(value) != 2
                || !Builtins.nullList(trailing)
                || !abi.equals(BigInteger.ONE)
                || chainId.length < 1 || chainId.length > 128
                || bridgeEpoch.signum() < 0
                || claimId.length != 32
                || destination.length < 1 || destination.length > 256
                || lovelace.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Settlement(claimId, lovelace));
    }

    record Settlement(byte[] claimId, BigInteger lovelace) {
    }
}
