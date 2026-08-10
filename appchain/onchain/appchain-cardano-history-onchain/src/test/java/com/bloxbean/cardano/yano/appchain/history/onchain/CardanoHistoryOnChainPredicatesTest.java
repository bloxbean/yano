package com.bloxbean.cardano.yano.appchain.history.onchain;

import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.proofs.onchain.AuthenticatedSnapshotOnChainVerifier;
import com.bloxbean.cardano.yano.appchain.proofs.onchain.MpfOnChainVerifier;
import com.bloxbean.cardano.yano.appchain.proofs.onchain.MpfPairOnChainVerifier;
import com.bloxbean.cardano.yano.api.appchain.l1view.ProtocolParamsCanonicalCodec;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochGovernanceContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.Arrays;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CardanoHistoryOnChainPredicatesTest extends ContractTest {
    @BeforeAll
    static void initializeCrypto() {
        initCrypto();
    }

    @Test
    void derivesExactCanonicalKeysUsedByGenericValidators() {
        byte[] credential = filled(1, 28);
        byte[] tx = filled(2, 32);
        assertThat(CardanoHistoryOnChainPredicates.parametersKey(170)).isEqualTo(
                CompositeCommitmentV1.componentKey(CardanoHistoryOnChainPredicates.PARAMS_COMPONENT,
                        EpochParamsContract.stateKey(170)));
        assertThat(CardanoHistoryOnChainPredicates.stakeSnapshotKey(0, credential))
                .isEqualTo(EpochStakeContract.credentialOrderKey(0, credential));
        assertThat(CardanoHistoryOnChainPredicates.proposalKey(170, tx, 2)).isEqualTo(
                CompositeCommitmentV1.componentKey(
                        CardanoHistoryOnChainPredicates.GOVERNANCE_COMPONENT,
                        EpochGovernanceContract.proposalKey(170, tx, 2)));
        assertThat(CardanoHistoryOnChainPredicates.drepSnapshotKey(1, credential))
                .isEqualTo(EpochGovernanceContract.drepOrderKey(1, credential));
    }

    @Test
    void exposesOnlyMpfValidatorsAndFrozenPredicateTags() {
        assertThat(MpfOnChainVerifier.class).isNotNull();
        assertThat(MpfPairOnChainVerifier.class).isNotNull();
        assertThat(AuthenticatedSnapshotOnChainVerifier.class).isNotNull();
        assertThat(CardanoHistoryOnChainPredicates.MPF_PROFILE).isEqualTo("mpf-blake2b256-v1");
        assertThat(new int[]{CardanoHistoryOnChainPredicates.STAKE_MINIMUM,
                CardanoHistoryOnChainPredicates.STAKE_POOL,
                CardanoHistoryOnChainPredicates.STAKE_MINIMUM_AND_POOL,
                CardanoHistoryOnChainPredicates.STAKE_EXACT_AND_POOL,
                CardanoHistoryOnChainPredicates.ABSENT_WITH_COMPLETENESS,
                CardanoHistoryOnChainPredicates.PROPOSAL_EXACT,
                CardanoHistoryOnChainPredicates.DREP_MINIMUM,
                CardanoHistoryOnChainPredicates.DREP_EXACT})
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void parameterPredicateBindsKeyRootEpochAndUnsignedRange() {
        byte[] key = CardanoHistoryOnChainPredicates.parameterFieldKey(170, "max-block-size");
        byte[] value = unsignedParameterField(170, "max-block-size", 65536);
        byte[] suffix = leafSuffix(Blake2bUtil.blake2bHash256(key));
        byte[] root = Blake2bUtil.blake2bHash256(concat(
                suffix, Blake2bUtil.blake2bHash256(value)));
        var proof = new MpfOnChainVerifier.Proof(key, value, suffix, JulcList.empty());

        assertThat(CardanoHistoryParametersValidator.verifyAtRoot(proof, root, key,
                BigInteger.valueOf(170), BigInteger.valueOf(
                        CardanoHistoryParametersValidator.UINT_EXACT),
                BigInteger.ZERO, BigInteger.valueOf(65536), BigInteger.valueOf(65536),
                new byte[0])).isTrue();
        assertThat(CardanoHistoryParametersValidator.verifyAtRoot(proof, root, key,
                BigInteger.valueOf(170), BigInteger.valueOf(
                        CardanoHistoryParametersValidator.UINT_MINIMUM),
                BigInteger.ZERO, BigInteger.valueOf(65537), BigInteger.valueOf(65537),
                new byte[0])).isFalse();
        assertThat(CardanoHistoryParametersValidator.verifyAtRoot(proof, root, key,
                BigInteger.valueOf(170), BigInteger.valueOf(
                        CardanoHistoryParametersValidator.UINT_RANGE),
                BigInteger.ZERO, BigInteger.valueOf(65535), BigInteger.valueOf(65536),
                new byte[0])).isTrue();
        assertThat(CardanoHistoryParametersValidator.verifyAtRoot(proof, filled(9, 32), key,
                BigInteger.valueOf(170), BigInteger.ONE, BigInteger.ZERO,
                BigInteger.valueOf(65536), BigInteger.valueOf(65536), new byte[0])).isFalse();
        assertThat(CardanoHistoryParametersValidator.verifyAtRoot(proof, root, filled(8, key.length),
                BigInteger.valueOf(170), BigInteger.ONE, BigInteger.ZERO,
                BigInteger.valueOf(65536), BigInteger.valueOf(65536), new byte[0])).isFalse();
    }

    @Test
    void parameterPredicateDecodesProductionPositiveBignumLeaves() {
        byte[] key = CardanoHistoryOnChainPredicates.parameterFieldKey(305, "key-deposit");
        byte[] value = ProtocolParamsCanonicalCodec.encodeLeaf(305, "key-deposit",
                ProtocolParamsCanonicalCodec.TYPE_LOVELACE, BigInteger.valueOf(2_000_000));
        byte[] suffix = leafSuffix(Blake2bUtil.blake2bHash256(key));
        byte[] root = Blake2bUtil.blake2bHash256(concat(
                suffix, Blake2bUtil.blake2bHash256(value)));
        var proof = new MpfOnChainVerifier.Proof(key, value, suffix, JulcList.empty());

        assertThat(java.util.HexFormat.of().formatHex(value))
                .isEqualTo("85011901316b6b65792d6465706f73697402c2431e8480");
        assertThat(CardanoHistoryParametersValidator.verifyAtRoot(proof, root, key,
                BigInteger.valueOf(305), BigInteger.valueOf(
                        CardanoHistoryParametersValidator.UINT_EXACT),
                BigInteger.valueOf(ProtocolParamsCanonicalCodec.TYPE_LOVELACE),
                BigInteger.valueOf(2_000_000), BigInteger.valueOf(2_000_000),
                new byte[0])).isTrue();
        assertThat(CardanoHistoryParametersValidator.verifyAtRoot(proof, root, key,
                BigInteger.valueOf(305), BigInteger.valueOf(
                        CardanoHistoryParametersValidator.UINT_EXACT),
                BigInteger.valueOf(ProtocolParamsCanonicalCodec.TYPE_LOVELACE),
                BigInteger.valueOf(2_000_001), BigInteger.valueOf(2_000_001),
                new byte[0])).isFalse();
    }

    @Test
    void productBoundValidatorsCompileToOnChainPrograms() {
        Path sources = Path.of("build/generated/julc-test-sources");
        assertThat(compileValidator(CardanoHistoryParametersValidator.class, sources).program()).isNotNull();
        assertThat(compileValidator(CardanoHistoryPairValidator.class, sources).program()).isNotNull();
    }

    @Test
    void pairBindingRejectsCallerSelectedKeyAndEpoch() {
        byte[] fact = filled(3, 32);
        byte[] completeness = filled(4, 32);
        byte[] value = new byte[]{(byte) 0x89, 1, 24, (byte) 170};

        assertThat(MpfPairOnChainVerifier.bindingsMatch(fact, completeness, value,
                fact, completeness, BigInteger.valueOf(170))).isTrue();
        assertThat(MpfPairOnChainVerifier.bindingsMatch(filled(5, 32), completeness, value,
                fact, completeness, BigInteger.valueOf(170))).isFalse();
        assertThat(MpfPairOnChainVerifier.bindingsMatch(fact, completeness, value,
                fact, completeness, BigInteger.valueOf(169))).isFalse();
    }

    private static byte[] unsignedParameterField(long epoch, String fieldId, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x85);
        writeUInt(out, 1);
        writeUInt(out, epoch);
        byte[] id = fieldId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.write(0x60 + id.length);
        out.writeBytes(id);
        writeUInt(out, 0);
        writeUInt(out, value);
        return out.toByteArray();
    }

    private static void writeUInt(ByteArrayOutputStream out, long value) {
        if (value < 24) out.write((int) value);
        else if (value <= 0xff) { out.write(24); out.write((int) value); }
        else if (value <= 0xffff) {
            out.write(25); out.write((int) (value >>> 8)); out.write((int) value);
        } else {
            out.write(26); out.write((int) (value >>> 24)); out.write((int) (value >>> 16));
            out.write((int) (value >>> 8)); out.write((int) value);
        }
    }

    private static byte[] leafSuffix(byte[] path) {
        byte[] result = new byte[33]; result[0] = (byte) 0xff;
        System.arraycopy(path, 0, result, 1, path.length); return result;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, result, left.length, right.length); return result;
    }

    private static byte[] filled(int value, int size) {
        byte[] bytes = new byte[size]; Arrays.fill(bytes, (byte) value); return bytes;
    }
}
