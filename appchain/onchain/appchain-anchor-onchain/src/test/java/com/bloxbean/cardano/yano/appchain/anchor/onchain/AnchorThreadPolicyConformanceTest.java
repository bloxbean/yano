package com.bloxbean.cardano.yano.appchain.anchor.onchain;

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
 * Conformance vectors for the one-shot state-thread NFT policy
 * (ADR app-layer/008.4 §2.3): mint requires consuming the parameterized seed
 * UTxO and minting exactly one token; burning is never permitted.
 */
class AnchorThreadPolicyConformanceTest extends ContractTest {

    static final long MAX_TX_CPU = 10_000_000_000L;
    static final long MAX_TX_MEM = 14_000_000L;

    static final byte[] SEED_TX_ID = fill(32, 0x5E);
    static final BigInteger SEED_INDEX = BigInteger.ONE;
    /** Synthetic own-policy hash — the context builder sets it as the minting purpose. */
    static final byte[] OWN_POLICY = fill(28, 0x0F);

    static Program julcProgram;

    @BeforeAll
    static void setUp() {
        initCrypto();
    }

    /** The implementation under test — the Aiken twin overrides this. */
    Program program() {
        if (julcProgram == null) {
            julcProgram = compileValidator(AnchorThreadPolicy.class)
                    .program()
                    .applyParams(PlutusData.bytes(SEED_TX_ID), PlutusData.integer(SEED_INDEX));
        }
        return julcProgram;
    }

    @Test
    void mintWithSeedConsumed_succeedsWithinBudget() {
        var ctx = mintCtx(true, mintValue(BigInteger.ONE));
        var result = evaluate(program(), ctx);
        assertSuccess(result);
        assertBudgetUnder(result, MAX_TX_CPU, MAX_TX_MEM);
        System.out.println("[" + getClass().getSimpleName() + "] mint budget: "
                + result.budgetConsumed());
    }

    @Test
    void mintWithoutSeed_fails() {
        assertFailure(evaluate(program(), mintCtx(false, mintValue(BigInteger.ONE))));
    }

    @Test
    void mintQuantityTwo_fails() {
        assertFailure(evaluate(program(), mintCtx(true, mintValue(BigInteger.TWO))));
    }

    @Test
    void burnAttempt_fails() {
        assertFailure(evaluate(program(), mintCtx(true, mintValue(BigInteger.valueOf(-1)))));
    }

    @Test
    void extraTokenName_fails() {
        var mint = mintValue(BigInteger.ONE).merge(Value.singleton(
                new PolicyId(OWN_POLICY), new TokenName(new byte[]{0x01}), BigInteger.ONE));
        assertFailure(evaluate(program(), mintCtx(true, mint)));
    }

    @Test
    void extraTokenNameAtOtherQuantity_fails() {
        // A qty!=1 rider must not slip past a "count qty==1 tokens" check
        var mint = mintValue(BigInteger.ONE).merge(Value.singleton(
                new PolicyId(OWN_POLICY), new TokenName(new byte[]{0x01}), BigInteger.valueOf(5)));
        assertFailure(evaluate(program(), mintCtx(true, mint)));
    }

    @Test
    void namedToken_succeeds() {
        // The token name is a free display label (the node mints with the
        // chain-id); identity comes from the one-shot policy id, not the name
        var mint = Value.singleton(new PolicyId(OWN_POLICY),
                new TokenName("orders-chain".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                BigInteger.ONE);
        assertSuccess(evaluate(program(), mintCtx(true, mint)));
    }

    @Test
    void namedToken_quantityTwo_fails() {
        var mint = Value.singleton(new PolicyId(OWN_POLICY),
                new TokenName(new byte[]{0x01}), BigInteger.TWO);
        assertFailure(evaluate(program(), mintCtx(true, mint)));
    }

    // -----------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------

    PlutusData mintCtx(boolean consumeSeed, Value mint) {
        var seedRef = new TxOutRef(new TxId(SEED_TX_ID), SEED_INDEX);
        var otherRef = new TxOutRef(new TxId(fill(32, 0x77)), BigInteger.ZERO);
        var wallet = TestDataBuilder.pubKeyAddress(TestDataBuilder.randomPubKeyHash_typed());

        var builder = mintingContext(new PolicyId(OWN_POLICY))
                .redeemer(PlutusData.integer(0))
                .input(new TxInInfo(otherRef,
                        TestDataBuilder.txOut(wallet, Value.lovelace(BigInteger.valueOf(5_000_000)))))
                .mint(mint);
        if (consumeSeed) {
            builder.input(new TxInInfo(seedRef,
                    TestDataBuilder.txOut(wallet, Value.lovelace(BigInteger.valueOf(2_000_000)))));
        }
        return builder.buildPlutusData();
    }

    static Value mintValue(BigInteger qty) {
        return Value.singleton(new PolicyId(OWN_POLICY), new TokenName(new byte[0]), qty);
    }

    static byte[] fill(int len, int b) {
        byte[] bytes = new byte[len];
        java.util.Arrays.fill(bytes, (byte) b);
        return bytes;
    }
}
