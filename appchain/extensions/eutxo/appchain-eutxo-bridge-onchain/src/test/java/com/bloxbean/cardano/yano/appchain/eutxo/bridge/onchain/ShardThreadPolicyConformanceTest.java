package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

/**
 * ADR-UTXO-009 SP-M6: the one-shot shard-thread policy mints exactly the 16
 * single-byte names {0x00 … 0x0F} (+1 each) while consuming the seed UTxO;
 * anything else fails.
 */
class ShardThreadPolicyConformanceTest extends ContractTest {
    private static final long MAX_TX_CPU = 10_000_000_000L;
    private static final long MAX_TX_MEM = 14_000_000L;

    private static final byte[] SEED_TX_ID = fill(32, 0x5E);
    private static final BigInteger SEED_INDEX = BigInteger.ONE;
    private static final byte[] OWN_POLICY = fill(28, 0x71);

    private static Program program;

    @BeforeAll
    static void setUp() {
        initCrypto();
    }

    private Program program() {
        if (program == null) {
            program = compileValidator(ShardThreadPolicy.class)
                    .program()
                    .applyParams(
                            PlutusData.bytes(SEED_TX_ID),
                            PlutusData.integer(SEED_INDEX));
        }
        return program;
    }

    @Test
    void mintingAllSixteenShardsWithSeedSucceedsWithinBudget() {
        var result = evaluate(program(), mintCtx(true, allShards()));
        assertSuccess(result);
        assertBudgetUnder(result, MAX_TX_CPU, MAX_TX_MEM);
        System.out.println("[ShardThreadPolicy] mint budget: "
                + result.budgetConsumed());
    }

    @Test
    void adversarialMintsFail() {
        // No seed consumed.
        assertFailure(evaluate(program(), mintCtx(false, allShards())));
        // Only 15 shards.
        assertFailure(evaluate(program(), mintCtx(true, shards(15))));
        // A 17th name outside 0x00..0x0F.
        assertFailure(evaluate(program(), mintCtx(true, allShards().merge(
                Value.singleton(new PolicyId(OWN_POLICY),
                        new TokenName(new byte[] {0x10}), BigInteger.ONE)))));
        // A doubled quantity.
        assertFailure(evaluate(program(), mintCtx(true, shards(15).merge(
                Value.singleton(new PolicyId(OWN_POLICY),
                        new TokenName(new byte[] {0x00}), BigInteger.TWO)))));
        // A burn rider.
        assertFailure(evaluate(program(), mintCtx(true, allShards().merge(
                Value.singleton(new PolicyId(OWN_POLICY),
                        new TokenName(new byte[] {0x1F}),
                        BigInteger.valueOf(-1))))));
        // A multi-byte name.
        assertFailure(evaluate(program(), mintCtx(true, shards(15).merge(
                Value.singleton(new PolicyId(OWN_POLICY),
                        new TokenName(new byte[] {0x0F, 0x00}), BigInteger.ONE)))));
    }

    private static Value allShards() {
        return shards(16);
    }

    private static Value shards(int count) {
        Value value = Value.zero();
        for (int index = 0; index < count; index++) {
            value = value.merge(Value.singleton(
                    new PolicyId(OWN_POLICY),
                    new TokenName(new byte[] {(byte) index}),
                    BigInteger.ONE));
        }
        return value;
    }

    private PlutusData mintCtx(boolean consumeSeed, Value mint) {
        var seedRef = new TxOutRef(new TxId(SEED_TX_ID), SEED_INDEX);
        var otherRef = new TxOutRef(new TxId(fill(32, 0x77)), BigInteger.ZERO);
        var wallet = TestDataBuilder.pubKeyAddress(
                TestDataBuilder.randomPubKeyHash_typed());
        var builder = mintingContext(new PolicyId(OWN_POLICY))
                .redeemer(PlutusData.integer(0))
                .input(new TxInInfo(otherRef, TestDataBuilder.txOut(
                        wallet, Value.lovelace(BigInteger.valueOf(5_000_000)))))
                .mint(mint);
        if (consumeSeed) {
            builder.input(new TxInInfo(seedRef, TestDataBuilder.txOut(
                    wallet, Value.lovelace(BigInteger.valueOf(2_000_000)))));
        }
        return builder.buildPlutusData();
    }

    private static byte[] fill(int length, int value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
