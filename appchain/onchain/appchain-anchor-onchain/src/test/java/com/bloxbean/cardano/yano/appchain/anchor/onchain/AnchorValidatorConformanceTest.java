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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

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
    static final List<byte[]> MEMBERS_32 = members(32);
    static final List<byte[]> MEMBERS_33 = members(33);
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
    void currentVersionNotOne_failsEvenWhenSuccessorMatches() {
        assertFailure(evaluate(program(),
                advanceCtx(baseline().version(2).nextVersion(2)).buildPlutusData()));
    }

    @Test
    void chainIdLengthOne_succeeds() {
        byte[] chainId = fill(1, 0xC1);
        assertSuccess(evaluate(program(), advanceCtx(baseline()
                .chainId(chainId).nextChainId(chainId)).buildPlutusData()));
    }

    @Test
    void chainIdLength128_succeeds() {
        byte[] chainId = fill(128, 0xC1);
        assertSuccess(evaluate(program(), advanceCtx(baseline()
                .chainId(chainId).nextChainId(chainId)).buildPlutusData()));
    }

    @Test
    void emptyCurrentChainId_failsEvenWhenSuccessorMatches() {
        byte[] chainId = new byte[0];
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .chainId(chainId).nextChainId(chainId)).buildPlutusData()));
    }

    @Test
    void emptySuccessorChainId_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .nextChainId(new byte[0])).buildPlutusData()));
    }

    @Test
    void currentChainIdLength129_failsEvenWhenSuccessorMatches() {
        byte[] chainId = fill(129, 0xC1);
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .chainId(chainId).nextChainId(chainId)).buildPlutusData()));
    }

    @Test
    void successorChainIdLength129_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .nextChainId(fill(129, 0xC1))).buildPlutusData()));
    }

    @Test
    void negativeCurrentHeight_failsEvenForMonotonicSuccessor() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .height(-1).nextHeight(0)).buildPlutusData()));
    }

    @Test
    void negativeSuccessorHeight_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .nextHeight(-1)).buildPlutusData()));
    }

    @Test
    void currentBlockHashLength31_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .blockHash(fill(31, 0xB0))).buildPlutusData()));
    }

    @Test
    void currentBlockHashLength33_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .blockHash(fill(33, 0xB0))).buildPlutusData()));
    }

    @Test
    void successorBlockHashLength31_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .nextBlockHash(fill(31, 0xB1))).buildPlutusData()));
    }

    @Test
    void successorBlockHashLength33_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .nextBlockHash(fill(33, 0xB1))).buildPlutusData()));
    }

    @Test
    void currentStateRootLength31_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .stateRoot(fill(31, 0x50))).buildPlutusData()));
    }

    @Test
    void currentStateRootLength33_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .stateRoot(fill(33, 0x50))).buildPlutusData()));
    }

    @Test
    void successorStateRootLength31_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .nextStateRoot(fill(31, 0x51))).buildPlutusData()));
    }

    @Test
    void successorStateRootLength33_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .nextStateRoot(fill(33, 0x51))).buildPlutusData()));
    }

    @Test
    void wrongCurrentDatumAlternative_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .currentDatumAlternative(1)).buildPlutusData()));
    }

    @Test
    void wrongSuccessorDatumAlternative_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .nextDatumAlternative(1)).buildPlutusData()));
    }

    @Test
    void extraCurrentDatumField_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .extraCurrentDatumField(true)).buildPlutusData()));
    }

    @Test
    void extraSuccessorDatumField_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .extraNextDatumField(true)).buildPlutusData()));
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
    void memberProfileBoundary32Of32_succeedsWithinBudget() {
        var result = evaluate(program(), advanceCtx(baseline()
                .members(MEMBERS_32)
                .threshold(32)
                .nextMembers(MEMBERS_32)
                .nextThreshold(32)
                .signers(MEMBERS_32))
                .buildPlutusData());
        assertSuccess(result);
        assertBudgetUnder(result, MAX_TX_CPU, MAX_TX_MEM);
        System.out.println("[" + getClass().getSimpleName() + "] 32-of-32 advance budget: "
                + result.budgetConsumed());
    }

    @Test
    void currentMemberCount33_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .members(MEMBERS_33)
                .threshold(2)
                .signers(List.of(MEMBERS_33.get(0), MEMBERS_33.get(1))))
                .buildPlutusData()));
    }

    @Test
    void successorMemberCount33_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .nextMembers(MEMBERS_33)
                .nextThreshold(2))
                .buildPlutusData()));
    }

    @Test
    void emptyCurrentMembers_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .members(List.of())
                .threshold(1)
                .signers(List.of()))
                .buildPlutusData()));
    }

    @Test
    void emptySuccessorMembers_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .nextMembers(List.of())
                .nextThreshold(1))
                .buildPlutusData()));
    }

    @Test
    void malformedCurrentMemberKeyLength_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .members(List.of(memberKey(1), fill(31, 2), memberKey(3)))
                .threshold(2))
                .buildPlutusData()));
    }

    @Test
    void malformedSuccessorMemberKeyLength_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .nextMembers(List.of(memberKey(1), fill(33, 2), memberKey(3)))
                .nextThreshold(2))
                .buildPlutusData()));
    }

    @Test
    void unsortedCurrentMembers_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .members(List.of(memberKey(2), memberKey(1), memberKey(3)))
                .threshold(2))
                .buildPlutusData()));
    }

    @Test
    void unsortedSuccessorMembers_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .nextMembers(List.of(memberKey(1), memberKey(3), memberKey(2)))
                .nextThreshold(2))
                .buildPlutusData()));
    }

    @Test
    void duplicateCurrentMembers_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .members(List.of(memberKey(1), memberKey(2), memberKey(2)))
                .threshold(2))
                .buildPlutusData()));
    }

    @Test
    void duplicateSuccessorMembers_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline()
                .nextMembers(List.of(memberKey(1), memberKey(2), memberKey(2)))
                .nextThreshold(2))
                .buildPlutusData()));
    }

    @Test
    void currentThresholdZero_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline().threshold(0))
                .buildPlutusData()));
    }

    @Test
    void currentThresholdAboveMemberCount_fails() {
        assertFailure(evaluate(program(), advanceCtx(baseline().threshold(4))
                .buildPlutusData()));
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
        byte[] chainId = CHAIN_ID;
        byte[] nextChainId = CHAIN_ID;
        long nextVersion = 1;
        long height = 10;
        long nextHeight = 11;
        byte[] blockHash = BLOCK_HASH;
        byte[] nextBlockHash = NEXT_BLOCK_HASH;
        byte[] stateRoot = STATE_ROOT;
        byte[] nextStateRoot = NEXT_STATE_ROOT;
        List<byte[]> members = MEMBERS;
        long threshold = THRESHOLD;
        List<byte[]> nextMembers = MEMBERS;
        long nextThreshold = THRESHOLD;
        List<byte[]> signers = List.of(MEMBERS.get(0), MEMBERS.get(1));
        boolean outputCarriesToken = true;
        boolean inlineNextDatum = true;
        boolean duplicateContinuingOutput = false;
        boolean payToWalletInstead = false;
        BigInteger outputLovelace = LOCKED;
        long redeemer = 0;
        int currentDatumAlternative = 0;
        int nextDatumAlternative = 0;
        boolean extraCurrentDatumField = false;
        boolean extraNextDatumField = false;

        Vector version(long v) { this.version = v; return this; }
        Vector chainId(byte[] v) { this.chainId = v; return this; }
        Vector nextChainId(byte[] v) { this.nextChainId = v; return this; }
        Vector nextVersion(long v) { this.nextVersion = v; return this; }
        Vector height(long v) { this.height = v; return this; }
        Vector nextHeight(long v) { this.nextHeight = v; return this; }
        Vector blockHash(byte[] v) { this.blockHash = v; return this; }
        Vector nextBlockHash(byte[] v) { this.nextBlockHash = v; return this; }
        Vector stateRoot(byte[] v) { this.stateRoot = v; return this; }
        Vector nextStateRoot(byte[] v) { this.nextStateRoot = v; return this; }
        Vector members(List<byte[]> v) { this.members = v; return this; }
        Vector threshold(long v) { this.threshold = v; return this; }
        Vector nextMembers(List<byte[]> v) { this.nextMembers = v; return this; }
        Vector nextThreshold(long v) { this.nextThreshold = v; return this; }
        Vector signers(List<byte[]> v) { this.signers = v; return this; }
        Vector outputCarriesToken(boolean v) { this.outputCarriesToken = v; return this; }
        Vector inlineNextDatum(boolean v) { this.inlineNextDatum = v; return this; }
        Vector duplicateContinuingOutput(boolean v) { this.duplicateContinuingOutput = v; return this; }
        Vector payToWalletInstead(boolean v) { this.payToWalletInstead = v; return this; }
        Vector outputLovelace(BigInteger v) { this.outputLovelace = v; return this; }
        Vector redeemer(long v) { this.redeemer = v; return this; }
        Vector currentDatumAlternative(int v) { this.currentDatumAlternative = v; return this; }
        Vector nextDatumAlternative(int v) { this.nextDatumAlternative = v; return this; }
        Vector extraCurrentDatumField(boolean v) { this.extraCurrentDatumField = v; return this; }
        Vector extraNextDatumField(boolean v) { this.extraNextDatumField = v; return this; }
    }

    static Vector baseline() {
        return new Vector();
    }

    ScriptContextTestBuilder advanceCtx(Vector v) {
        var scriptAddress = new Address(
                new Credential.ScriptCredential(new ScriptHash(fill(28, 0x5C))),
                Optional.empty());
        var currentDatum = anchorDatum(v.currentDatumAlternative,
                v.extraCurrentDatumField, v.version, v.chainId, v.height,
                v.blockHash, v.stateRoot, v.members, v.threshold);
        var nextDatum = anchorDatum(v.nextDatumAlternative,
                v.extraNextDatumField, v.nextVersion, v.nextChainId, v.nextHeight,
                v.nextBlockHash, v.nextStateRoot, v.nextMembers, v.nextThreshold);

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
        return anchorDatum(0, false, version, chainId, height, blockHash,
                stateRoot, memberKeys, threshold);
    }

    static PlutusData anchorDatum(int alternative, boolean extraField,
                                  long version, byte[] chainId, long height,
                                  byte[] blockHash, byte[] stateRoot,
                                  List<byte[]> memberKeys, long threshold) {
        List<PlutusData> fields = new ArrayList<>(List.of(
                PlutusData.integer(version), PlutusData.bytes(chainId),
                PlutusData.integer(height), PlutusData.bytes(blockHash),
                PlutusData.bytes(stateRoot),
                PlutusData.list(memberKeys.stream()
                        .map(PlutusData::bytes).toArray(PlutusData[]::new)),
                PlutusData.integer(threshold)));
        if (extraField) {
            fields.add(PlutusData.integer(999));
        }
        return PlutusData.constr(alternative, fields.toArray(PlutusData[]::new));
    }

    static byte[] fill(int len, int b) {
        byte[] bytes = new byte[len];
        java.util.Arrays.fill(bytes, (byte) b);
        return bytes;
    }

    /** Deterministic 32-byte Ed25519 member key stand-in. */
    static byte[] memberKey(int i) {
        byte[] key = new byte[32];
        key[30] = (byte) (i >>> 8);
        key[31] = (byte) i;
        return key;
    }

    static List<byte[]> members(int count) {
        List<byte[]> keys = new ArrayList<>(count);
        IntStream.rangeClosed(1, count).mapToObj(AnchorValidatorConformanceTest::memberKey)
                .forEach(keys::add);
        return List.copyOf(keys);
    }
}
