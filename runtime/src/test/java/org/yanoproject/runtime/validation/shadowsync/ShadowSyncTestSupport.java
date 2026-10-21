package org.yanoproject.runtime.validation.shadowsync;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Stub engines, environments and synthetic blocks for the shadow-sync tests. */
final class ShadowSyncTestSupport {

    static final String ADDRESS =
            "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";

    private ShadowSyncTestSupport() {
    }

    static ValidationEnv env(int pv) {
        return new ValidationEnv(100, 0, pv, 0, NetworkId.TESTNET, new SlotConfig(1000, 0, 0), new byte[32]);
    }

    static ProtocolParams params(int pv) {
        ProtocolParams params = new ProtocolParams();
        params.setProtocolMajorVer(pv);
        params.setProtocolMinorVer(0);
        return params;
    }

    /** Distinct placeholder transactions (the stub engines never decode them): {@code [ {}, {}, bool, uint i ]}. */
    static SyncBlock block(int count, Set<Integer> invalid) {
        List<byte[]> txs = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            txs.add(tx(i, !invalid.contains(i)));
            ids.add(id(i));
        }
        return new SyncBlock(txs, ids, invalid);
    }

    static byte[] tx(int index, boolean isValid) {
        return HexUtil.decodeHexString("84a0a0" + (isValid ? "f5" : "f4") + String.format("18%02x", index));
    }

    static String id(int index) {
        return String.format("%064x", index + 1);
    }

    /**
     * @return the index a placeholder transaction was built for: {@link #tx} ends with it; a block-reassembled one
     *         ({@code [{0: i}, {}, bool, null]}, {@code ShadowSyncValidatorTest.block}) carries it in its body
     */
    static int indexOf(byte[] tx) {
        return tx[1] == (byte) 0xa1 ? tx[4] & 0xff : tx[tx.length - 1] & 0xff;
    }

    static boolean isValidFlag(byte[] tx) {
        return tx[1] == (byte) 0xa1 ? tx[6] == (byte) 0xf5 : tx[3] == (byte) 0xf5;
    }

    static TransactionOutput output(long lovelace) {
        return TransactionOutput.builder().address(ADDRESS).value(Value.builder()
                .coin(BigInteger.valueOf(lovelace)).build()).build();
    }

    /** A valid outcome producing {@code (id(index), 0)}. */
    static TxValidationOutcome.Valid valid(byte[] tx, boolean phase2Valid) {
        int index = indexOf(tx);
        TxEffects effects = new TxEffects(id(index), phase2Valid, List.of(),
                List.of(new UtxoEntry(new Outpoint(id(index), 0), output(1_000_000))), List.of());
        return new TxValidationOutcome.Valid(effects, new ValidatedTx(tx, HexUtil.decodeHexString(id(index)), 10, 0,
                new byte[32], phase2Valid, TxValidationRequest.Origin.SYNC), false);
    }

    static TxValidationOutcome.Invalid invalid(LedgerRuleName rule, String constructor) {
        return TxValidationOutcome.Invalid.of(new LedgerFailure(rule, constructor, LedgerFailure.Phase.PHASE_1, "x"));
    }

    static LedgerValidationEngine engine(String name, Function<TxValidationRequest, TxValidationOutcome> body) {
        return new LedgerValidationEngine() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public TxValidationOutcome validate(TxValidationRequest request) {
                return body.apply(request);
            }
        };
    }

    /** An engine that accepts every placeholder with the phase-2 verdict its {@code is_valid} flag claims. */
    static LedgerValidationEngine agreeing(String name) {
        return engine(name, request -> valid(request.txCbor(), isValidFlag(request.txCbor())));
    }
}
