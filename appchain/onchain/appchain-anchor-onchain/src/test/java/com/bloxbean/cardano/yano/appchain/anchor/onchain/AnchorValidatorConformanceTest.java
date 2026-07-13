package com.bloxbean.cardano.yano.appchain.anchor.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Conformance vectors for the anchor spending validator (ADR app-layer/008.4
 * §4, ABI: core-api/src/main/cddl/appchain/anchor-v1.cddl). These vectors are
 * implementation-independent: the Aiken opt-in implementation must pass the
 * same set against the same context/datum encodings.
 */
class AnchorValidatorConformanceTest extends ContractTest {

    /** Mainnet per-tx execution limits — every vector must fit well within. */
    static final long MAX_TX_CPU = 10_000_000_000L;
    static final long MAX_TX_MEM = 14_000_000L;

    static final byte[] THREAD_POLICY = fill(28, 0xAA);
    static final byte[] CHAIN_ID = fill(32, 0xC1);
    static final byte[] BLOCK_HASH = fill(32, 0xB0);
    static final byte[] NEXT_BLOCK_HASH = fill(32, 0xB1);
    static final byte[] STATE_ROOT = fill(32, 0x50);
    static final byte[] NEXT_STATE_ROOT = fill(32, 0x51);
    static final BigInteger LOCKED = BigInteger.valueOf(2_000_000);

    static final List<byte[]> MEMBERS =
            List.of(memberKey(1), memberKey(2), memberKey(3));
    static final long THRESHOLD = 2;

    static Program julcProgram;

    @BeforeAll
    static void setUp() {
        initCrypto();
    }

    /**
     * The implementation under test — the CHECKED-IN julc artifact (the exact
     * bytes the runtime ships; environment-independent). The Aiken twin and
     * the local-only source-compile drift test override this.
     */
    Program program() {
        if (julcProgram == null) {
            julcProgram = BundledJulcArtifacts.load(
                    "META-INF/plutus/AnchorValidator.plutus.json",
                    PlutusData.bytes(THREAD_POLICY));
        }
        return julcProgram;
    }

    // -----------------------------------------------------------------
    // Vectors
    // -----------------------------------------------------------------

    @Test
    void happyPathAdvance_succeedsWithinBudget() {
        var result = evaluate(program(), advanceCtx(baseline()).buildPlutusData());
        assertSuccess(result);
        assertBudgetUnder(result, MAX_TX_CPU, MAX_TX_MEM);
        System.out.println("[" + getClass().getSimpleName() + "] advance budget: "
                + result.budgetConsumed());
    }

    @Test
    void heightEqual_fails() {
        assertFailure(evaluate(program(),
                advanceCtx(baseline().nextHeight(10)).buildPlutusData()));
    }

    @Test
    void heightRegression_fails() {
        assertFailure(evaluate(program(),
                advanceCtx(baseline().nextHeight(9)).buildPlutusData()));
    }

    @Test
    void subThresholdSignatures_fails() {
        assertFailure(evaluate(program(),
                advanceCtx(baseline().signers(List.of(MEMBERS.get(0)))).buildPlutusData()));
    }

    @Test
    void nonMemberSignaturesDoNotCount_fails() {
        assertFailure(evaluate(program(),
                advanceCtx(baseline()
                        .signers(List.of(memberKey(7), memberKey(8), memberKey(9))))
                        .buildPlutusData()));
    }

    @Test
    void missingThreadToken_fails() {
        assertFailure(evaluate(program(),
                advanceCtx(baseline().outputCarriesToken(false)).buildPlutusData()));
    }

    @Test
    void nonInlineNextDatum_fails() {
        assertFailure(evaluate(program(),
                advanceCtx(baseline().inlineNextDatum(false)).buildPlutusData()));
    }

    @Test
    void chainIdSwap_fails() {
        assertFailure(evaluate(program(),
                advanceCtx(baseline().nextChainId(fill(32, 0xC2))).buildPlutusData()));
    }

    @Test
    void versionChange_fails() {
        assertFailure(evaluate(program(),
                advanceCtx(baseline().nextVersion(2)).buildPlutusData()));
    }

    @Test
    void membershipRotation_succeeds() {
        // Rule 4: successor set may differ; rule 3 authorizes against the
        // INPUT datum's members, so the outgoing quorum signs the handover.
        var result = evaluate(program(),
                advanceCtx(baseline()
                        .nextMembers(List.of(memberKey(4), memberKey(5)))
                        .nextThreshold(2))
                        .buildPlutusData());
        assertSuccess(result);
    }

    @Test
    void successorThresholdZero_fails() {
        assertFailure(evaluate(program(),
                advanceCtx(baseline().nextThreshold(0)).buildPlutusData()));
    }

    @Test
    void successorThresholdAboveMemberCount_fails() {
        assertFailure(evaluate(program(),
                advanceCtx(baseline().nextThreshold(4)).buildPlutusData()));
    }

    @Test
    void drainAttempt_fails() {
        assertFailure(evaluate(program(),
                advanceCtx(baseline().outputLovelace(LOCKED.subtract(BigInteger.ONE)))
                        .buildPlutusData()));
    }

    @Test
    void lovelaceTopUp_succeeds() {
        assertSuccess(evaluate(program(),
                advanceCtx(baseline().outputLovelace(LOCKED.add(BigInteger.valueOf(500_000))))
                        .buildPlutusData()));
    }

    @Test
    void redeemerIsReservedDiscriminant_notInspected() {
        // ABI note: v1 has a single action; the redeemer value is reserved
        // and not inspected — a valid advance succeeds regardless.
        assertSuccess(evaluate(program(),
                advanceCtx(baseline().redeemer(1)).buildPlutusData()));
    }

    @Test
    void multipleContinuingOutputs_fails() {
        assertFailure(evaluate(program(),
                advanceCtx(baseline().duplicateContinuingOutput(true)).buildPlutusData()));
    }

    @Test
    void noContinuingOutput_fails() {
        // Full-drain attack: every output pays a wallet, none continue the script
        assertFailure(evaluate(program(),
                advanceCtx(baseline().payToWalletInstead(true)).buildPlutusData()));
    }

    // -----------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------

    /** Mutable vector spec; baseline() is the passing advance h=10 → h=11. */
    static class Vector {
        long version = 1;
        byte[] nextChainId = CHAIN_ID;
        long nextVersion = 1;
        long height = 10;
        long nextHeight = 11;
        List<byte[]> nextMembers = MEMBERS;
        long nextThreshold = THRESHOLD;
        List<byte[]> signers = List.of(MEMBERS.get(0), MEMBERS.get(1));
        boolean outputCarriesToken = true;
        boolean inlineNextDatum = true;
        boolean duplicateContinuingOutput = false;
        boolean payToWalletInstead = false;
        BigInteger outputLovelace = LOCKED;
        long redeemer = 0;

        Vector nextChainId(byte[] v) { this.nextChainId = v; return this; }
        Vector nextVersion(long v) { this.nextVersion = v; return this; }
        Vector nextHeight(long v) { this.nextHeight = v; return this; }
        Vector nextMembers(List<byte[]> v) { this.nextMembers = v; return this; }
        Vector nextThreshold(long v) { this.nextThreshold = v; return this; }
        Vector signers(List<byte[]> v) { this.signers = v; return this; }
        Vector outputCarriesToken(boolean v) { this.outputCarriesToken = v; return this; }
        Vector inlineNextDatum(boolean v) { this.inlineNextDatum = v; return this; }
        Vector duplicateContinuingOutput(boolean v) { this.duplicateContinuingOutput = v; return this; }
        Vector payToWalletInstead(boolean v) { this.payToWalletInstead = v; return this; }
        Vector outputLovelace(BigInteger v) { this.outputLovelace = v; return this; }
        Vector redeemer(long v) { this.redeemer = v; return this; }
    }

    static Vector baseline() {
        return new Vector();
    }

    ScriptContextTestBuilder advanceCtx(Vector v) {
        var scriptAddress = new Address(
                new Credential.ScriptCredential(new ScriptHash(fill(28, 0x5C))),
                Optional.empty());
        var currentDatum = anchorDatum(v.version, CHAIN_ID, v.height, BLOCK_HASH,
                STATE_ROOT, MEMBERS, THRESHOLD);
        var nextDatum = anchorDatum(v.nextVersion, v.nextChainId, v.nextHeight,
                NEXT_BLOCK_HASH, NEXT_STATE_ROOT, v.nextMembers, v.nextThreshold);

        var ownRef = new TxOutRef(
                new com.bloxbean.cardano.julc.ledger.TxId(fill(32, 0x11)), BigInteger.ZERO);
        var ownInput = new TxInInfo(ownRef, new TxOut(scriptAddress,
                lockedValue(LOCKED, true),
                new OutputDatum.OutputDatumInline(currentDatum), Optional.empty()));

        var outAddress = v.payToWalletInstead
                ? TestDataBuilder.pubKeyAddress(TestDataBuilder.randomPubKeyHash_typed())
                : scriptAddress;
        var nextOut = new TxOut(outAddress,
                lockedValue(v.outputLovelace, v.outputCarriesToken),
                v.inlineNextDatum
                        ? new OutputDatum.OutputDatumInline(nextDatum)
                        : new OutputDatum.NoOutputDatum(),
                Optional.empty());

        var builder = spendingContext(ownRef, currentDatum)
                .redeemer(PlutusData.integer(v.redeemer))
                .input(ownInput)
                .output(nextOut);
        if (v.duplicateContinuingOutput) {
            builder.output(TestDataBuilder.txOut(scriptAddress, Value.lovelace(LOCKED)));
        }
        for (byte[] signer : v.signers) {
            builder.signer(Builtins.blake2b_224(signer));
        }
        return builder;
    }

    static Value lockedValue(BigInteger lovelace, boolean withToken) {
        var value = Value.lovelace(lovelace);
        if (withToken) {
            value = value.merge(Value.singleton(
                    new PolicyId(THREAD_POLICY), new TokenName(new byte[0]), BigInteger.ONE));
        }
        return value;
    }

    /** anchor-datum per anchor-v1.cddl, wrapped Constr(0, fields). */
    static PlutusData anchorDatum(long version, byte[] chainId, long height,
                                  byte[] blockHash, byte[] stateRoot,
                                  List<byte[]> memberKeys, long threshold) {
        return PlutusData.constr(0,
                PlutusData.integer(version),
                PlutusData.bytes(chainId),
                PlutusData.integer(height),
                PlutusData.bytes(blockHash),
                PlutusData.bytes(stateRoot),
                PlutusData.list(memberKeys.stream()
                        .map(PlutusData::bytes).toArray(PlutusData[]::new)),
                PlutusData.integer(threshold));
    }

    static byte[] fill(int len, int b) {
        byte[] bytes = new byte[len];
        java.util.Arrays.fill(bytes, (byte) b);
        return bytes;
    }

    /** Deterministic 32-byte Ed25519 member key stand-in. */
    static byte[] memberKey(int i) {
        return fill(32, i);
    }
}
