package com.bloxbean.cardano.yano.appchain.proofs.onchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.cbor.PlutusDataCborEncoder;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotSeriesDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotCanonicalCodec;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotSourceBoundary;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedSnapshotOnChainVerifierTest extends ContractTest {
    private static final byte[] THREAD_POLICY = filled(0x31, 28);
    private static final byte[] THREAD_ASSET = "history".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ANCHOR_SCRIPT = filled(0x32, 28);
    private static final byte[] CHAIN_ID = "cardano-history-chain".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CHAIN_GENESIS = filled(1, 32);
    private static final byte[] ANCHOR_APPLICATION = "cardano-history".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] APPLICATION_PROFILE = filled(2, 32);
    private static final byte[] PROFILE = StateCommitmentProfiles.MPF.id()
            .getBytes(StandardCharsets.US_ASCII);
    private static Program program;

    @BeforeAll
    static void crypto() {
        initCrypto();
    }

    @Test
    void descriptorAndSecondaryRootVerifierCompilesToPlutus() {
        Fixture fixture = fixture();
        assertThat(fixture.primary().proof().value()).isEqualTo(
                SnapshotCanonicalCodec.encodeDescriptor(fixture.descriptor()));
        assertThat(fixture.fact().root()).isEqualTo(fixture.descriptor().snapshotRoot());
        assertThat(program()).isNotNull();
    }

    @Test
    void evaluatesNestedStakeProofAgainstTheUniqueL1AnchorWithinBudget() {
        Fixture fixture = fixture();
        PlutusData redeemer = nestedData(fixture);
        var result = evaluate(program(), spendingContext(
                        new TxOutRef(new TxId(filled(0x61, 32)), BigInteger.ZERO), PlutusData.UNIT)
                .redeemer(redeemer)
                .referenceInput(anchorInput(fixture.primary().root()))
                .buildPlutusData());
        assertSuccess(result);
        assertBudgetUnder(result, 10_000_000_000L, 14_000_000L);
        assertThat(PlutusDataCborEncoder.encode(redeemer).length).isLessThan(16 * 1024);
    }

    @Test
    void rejectsNestedProofWhenTheL1AnchorCarriesAnotherPrimaryRoot() {
        Fixture fixture = fixture();
        byte[] wrong = fixture.primary().root();
        wrong[0] ^= 1;
        var result = evaluate(program(), spendingContext(
                        new TxOutRef(new TxId(filled(0x62, 32)), BigInteger.ZERO), PlutusData.UNIT)
                .redeemer(nestedData(fixture))
                .referenceInput(anchorInput(wrong))
                .buildPlutusData());
        assertFailure(result);
    }

    @Test
    void rejectsDescriptorWhoseSeriesSequenceSchemaOrBoundaryIsNotValidatorBound() {
        assertRejected(fixture("other", 170, "epoch-stake-value-v1", 170,
                "stake/170/credential-1".getBytes(StandardCharsets.US_ASCII)));
        assertRejected(fixture("stake", 171, "epoch-stake-value-v1", 170,
                "stake/170/credential-1".getBytes(StandardCharsets.US_ASCII)));
        assertRejected(fixture("stake", 170, "other-schema", 170,
                "stake/170/credential-1".getBytes(StandardCharsets.US_ASCII)));
        assertRejected(fixture("stake", 170, "epoch-stake-value-v1", 169,
                "stake/170/credential-1".getBytes(StandardCharsets.US_ASCII)));
        assertRejected(fixtureWithBoundary(169, 171, 170));
        assertRejected(fixtureWithBoundary(170, 172, 170));
    }

    @Test
    void rejectsRedeemerFactForAKeyNotBoundIntoTheValidator() {
        assertRejected(fixture("stake", 170, "epoch-stake-value-v1", 170,
                "stake/170/credential-2".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void predicateThresholdPoolAndPresenceAreValidatorBound() {
        Fixture fixture = fixture();
        assertFailure(evaluate(program(BigInteger.valueOf(2), BigInteger.valueOf(1_001), fixture.pool()),
                spendingContext(new TxOutRef(new TxId(filled(0x64, 32)), BigInteger.ZERO),
                        PlutusData.UNIT).redeemer(nestedData(fixture))
                        .referenceInput(anchorInput(fixture.primary().root())).buildPlutusData()));
        assertFailure(evaluate(program(BigInteger.valueOf(2), BigInteger.valueOf(1_000),
                        filled(0x55, 28)),
                spendingContext(new TxOutRef(new TxId(filled(0x65, 32)), BigInteger.ZERO),
                        PlutusData.UNIT).redeemer(nestedData(fixture))
                        .referenceInput(anchorInput(fixture.primary().root())).buildPlutusData()));
        assertFailure(evaluate(program(BigInteger.valueOf(4), BigInteger.ZERO, new byte[0]),
                spendingContext(new TxOutRef(new TxId(filled(0x66, 32)), BigInteger.ZERO),
                        PlutusData.UNIT).redeemer(nestedData(fixture))
                        .referenceInput(anchorInput(fixture.primary().root())).buildPlutusData()));
    }

    private void assertRejected(Fixture fixture) {
        var result = evaluate(program(), spendingContext(
                        new TxOutRef(new TxId(filled(0x63, 32)), BigInteger.ZERO), PlutusData.UNIT)
                .redeemer(nestedData(fixture))
                .referenceInput(anchorInput(fixture.primary().root()))
                .buildPlutusData());
        assertFailure(result);
    }

    private static Fixture fixture() {
        return fixture("epoch-stake.distribution", 170, "epoch-stake-v1", 170,
                "stake/170/credential-1".getBytes(StandardCharsets.US_ASCII));
    }

    private static Fixture fixture(String series, long sequence, String schema,
                                   long datasetEpoch, byte[] factKey) {
        return fixture(series, sequence, schema, 170, 171, datasetEpoch, factKey);
    }

    private static Fixture fixtureWithBoundary(long previousEpoch, long newEpoch,
                                               long datasetEpoch) {
        return fixture("epoch-stake.distribution", 170, "epoch-stake-v1",
                previousEpoch, newEpoch, datasetEpoch,
                "stake/170/credential-1".getBytes(StandardCharsets.US_ASCII));
    }

    private static Fixture fixture(String series, long sequence, String schema,
                                   long previousEpoch, long newEpoch, long datasetEpoch,
                                   byte[] factKey) {
        byte[] pool = filled(0x41, 28);
        byte[] factValue = stakeValue(1_000, pool);
        Leaf fact = leaf(factKey, factValue);

        SnapshotDescriptorV1 descriptor = new SnapshotDescriptorV1(
                CHAIN_GENESIS, APPLICATION_PROFILE, series, sequence, "stake-170",
                StateCommitmentProfiles.MPF.id(), StateCommitmentProfiles.MPF.formatFingerprint(),
                StateCommitmentProfiles.MPF.proofEncodingId(), fact.root(), filled(3, 32),
                "blake2b256", "epoch-stake-source-v1", schema, 1,
                10, 11, 10, 11, filled(0, 32),
                new SnapshotSourceBoundary.L1Epoch(
                        previousEpoch, newEpoch, datasetEpoch, 20, filled(4, 32)),
                AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage.DATASET, true);
        byte[] descriptorKey = ("snapshots/v1/epoch-stake.distribution/" + String.format(java.util.Locale.ROOT,
                "%020d", 170)).getBytes(StandardCharsets.US_ASCII);
        Leaf primary = leaf(descriptorKey, SnapshotCanonicalCodec.encodeDescriptor(descriptor));
        return new Fixture(descriptor, descriptorKey, factKey, pool, primary, fact);
    }

    private Program program() {
        if (program == null) {
            program = compileValidator(AuthenticatedSnapshotOnChainVerifier.class).program().applyParams(
                    PlutusData.bytes(THREAD_POLICY), PlutusData.bytes(THREAD_ASSET),
                    PlutusData.bytes(ANCHOR_SCRIPT), PlutusData.bytes(CHAIN_ID),
                    PlutusData.bytes(CHAIN_GENESIS), PlutusData.bytes(ANCHOR_APPLICATION),
                    PlutusData.bytes(APPLICATION_PROFILE), PlutusData.bytes(PROFILE),
                    PlutusData.bytes(StateCommitmentProfiles.MPF.formatFingerprint()),
                    PlutusData.bytes(PROFILE),
                    PlutusData.bytes(StateCommitmentProfiles.MPF.formatFingerprint()),
                    PlutusData.bytes(StateCommitmentProfiles.MPF.proofEncodingId()
                            .getBytes(StandardCharsets.US_ASCII)),
                    PlutusData.bytes(fixture().descriptorKey()),
                    PlutusData.bytes(fixture().factKey()),
                    PlutusData.bytes("epoch-stake.distribution".getBytes(StandardCharsets.US_ASCII)),
                    PlutusData.integer(170),
                    PlutusData.bytes("epoch-stake-v1".getBytes(StandardCharsets.US_ASCII)),
                    PlutusData.bytes("blake2b256".getBytes(StandardCharsets.US_ASCII)),
                    PlutusData.bytes("epoch-stake-source-v1".getBytes(StandardCharsets.US_ASCII)),
                    PlutusData.integer(170), PlutusData.integer(171), PlutusData.integer(170),
                    PlutusData.integer(2), PlutusData.integer(1_000),
                    PlutusData.bytes(fixture().pool()));
        }
        return program;
    }

    private Program program(BigInteger predicate, BigInteger coin, byte[] auxiliary) {
        Fixture fixture = fixture();
        return compileValidator(AuthenticatedSnapshotOnChainVerifier.class).program().applyParams(
                PlutusData.bytes(THREAD_POLICY), PlutusData.bytes(THREAD_ASSET),
                PlutusData.bytes(ANCHOR_SCRIPT), PlutusData.bytes(CHAIN_ID),
                PlutusData.bytes(CHAIN_GENESIS), PlutusData.bytes(ANCHOR_APPLICATION),
                PlutusData.bytes(APPLICATION_PROFILE), PlutusData.bytes(PROFILE),
                PlutusData.bytes(StateCommitmentProfiles.MPF.formatFingerprint()),
                PlutusData.bytes(PROFILE),
                PlutusData.bytes(StateCommitmentProfiles.MPF.formatFingerprint()),
                PlutusData.bytes(StateCommitmentProfiles.MPF.proofEncodingId()
                        .getBytes(StandardCharsets.US_ASCII)),
                PlutusData.bytes(fixture.descriptorKey()), PlutusData.bytes(fixture.factKey()),
                PlutusData.bytes("epoch-stake.distribution".getBytes(StandardCharsets.US_ASCII)),
                PlutusData.integer(170),
                PlutusData.bytes("epoch-stake-v1".getBytes(StandardCharsets.US_ASCII)),
                PlutusData.bytes("blake2b256".getBytes(StandardCharsets.US_ASCII)),
                PlutusData.bytes("epoch-stake-source-v1".getBytes(StandardCharsets.US_ASCII)),
                PlutusData.integer(170), PlutusData.integer(171), PlutusData.integer(170),
                PlutusData.integer(predicate), PlutusData.integer(coin),
                PlutusData.bytes(auxiliary));
    }

    private static PlutusData nestedData(Fixture fixture) {
        return PlutusData.constr(0, proofData(fixture.primary().proof()),
                proofData(fixture.fact().proof()));
    }

    private static PlutusData proofData(AuthenticatedSnapshotOnChainVerifier.Proof proof) {
        return PlutusData.constr(0, PlutusData.bytes(proof.key()), PlutusData.bytes(proof.value()),
                PlutusData.bytes(proof.leafSuffix()), PlutusData.list(),
                PlutusData.integer(proof.terminalCursor()),
                PlutusData.bytes(proof.conflictingKeyHash()),
                PlutusData.bytes(proof.conflictingValueHash()));
    }

    private static TxInInfo anchorInput(byte[] stateRoot) {
        PlutusData datum = PlutusData.constr(0, PlutusData.integer(1),
                PlutusData.bytes(CHAIN_ID), PlutusData.bytes(CHAIN_GENESIS),
                PlutusData.bytes(ANCHOR_APPLICATION), PlutusData.bytes(PROFILE),
                PlutusData.bytes(StateCommitmentProfiles.MPF.formatFingerprint()),
                PlutusData.integer(42), PlutusData.bytes(filled(0x34, 32)),
                PlutusData.bytes(stateRoot), PlutusData.list(PlutusData.bytes(filled(0x35, 32))),
                PlutusData.integer(1));
        Address address = new Address(new Credential.ScriptCredential(new ScriptHash(ANCHOR_SCRIPT)),
                Optional.empty());
        Value value = Value.lovelace(BigInteger.valueOf(2_000_000L)).merge(Value.singleton(
                new PolicyId(THREAD_POLICY), new TokenName(THREAD_ASSET), BigInteger.ONE));
        return new TxInInfo(new TxOutRef(new TxId(filled(0x36, 32)), BigInteger.ZERO),
                new TxOut(address, value, new OutputDatum.OutputDatumInline(datum), Optional.empty()));
    }

    private static Leaf leaf(byte[] key, byte[] value) {
        byte[] suffix = new byte[33];
        suffix[0] = (byte) 0xff;
        System.arraycopy(Blake2bUtil.blake2bHash256(key), 0, suffix, 1, 32);
        byte[] valueHash = Blake2bUtil.blake2bHash256(value);
        byte[] material = Arrays.copyOf(suffix, suffix.length + valueHash.length);
        System.arraycopy(valueHash, 0, material, suffix.length, valueHash.length);
        byte[] root = Blake2bUtil.blake2bHash256(material);
        return new Leaf(root, new AuthenticatedSnapshotOnChainVerifier.Proof(key, value, suffix,
                JulcList.empty(), BigInteger.ZERO, new byte[0], new byte[0]));
    }

    private static byte[] stakeValue(int coin, byte[] pool) {
        return new byte[]{(byte) 0x82, (byte) 0x19, (byte) (coin >>> 8), (byte) coin,
                (byte) 0x58, (byte) 0x1c,
                pool[0], pool[1], pool[2], pool[3], pool[4], pool[5], pool[6], pool[7],
                pool[8], pool[9], pool[10], pool[11], pool[12], pool[13], pool[14], pool[15],
                pool[16], pool[17], pool[18], pool[19], pool[20], pool[21], pool[22], pool[23],
                pool[24], pool[25], pool[26], pool[27]};
    }

    private static byte[] filled(int value, int length) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private record Leaf(byte[] root, AuthenticatedSnapshotOnChainVerifier.Proof proof) {
        private Leaf { root = root.clone(); }
        @Override public byte[] root() { return root.clone(); }
    }

    private record Fixture(SnapshotDescriptorV1 descriptor, byte[] descriptorKey,
                           byte[] factKey, byte[] pool, Leaf primary, Leaf fact) { }
}
