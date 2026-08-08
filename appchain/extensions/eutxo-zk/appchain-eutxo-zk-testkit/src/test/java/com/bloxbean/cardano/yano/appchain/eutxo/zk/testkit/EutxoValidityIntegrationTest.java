package com.bloxbean.cardano.yano.appchain.eutxo.zk.testkit;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoContract;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Authorization;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Domain;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2ParameterSnapshot;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoStateKeys;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityTransition;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.ledger.EutxoStateMachineProvider;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTransactionFixtures;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.MemoryAppState;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoFinalizedProofWitness;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoValidityWitness;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkAuthorizationProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.ZerojPoseidonValidityProvider;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.EdDSAJubjub;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubCurve;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoValidityIntegrationTest {
    @Test
    void stableDepositWithdrawalValidityTransitionReplaysExactly()
            throws Exception {
        EutxoTestWallet alice = wallet(3);
        EutxoTestWallet bob = wallet(4);
        BigInteger l2Secret = BigInteger.valueOf(303);
        var l2Key = EdDSAJubjub.keypairFromSecret(l2Secret);
        String vaultAddress = "addr_test1_bridge_vault";
        String vaultScriptHash = "11".repeat(28);
        Map<String, String> settings = new java.util.LinkedHashMap<>(
                ZerojPoseidonValidityProvider.requiredIdentitySettings());
        settings.put("machines.eutxo.profile", EutxoProfile.V1.id());
        settings.put("machines.eutxo.network", "devnet");
        settings.put("machines.eutxo.genesis.l2-address", alice.address());
        settings.put("machines.eutxo.genesis.l2-public-key",
                HexFormat.of().formatHex(l2Key.pk().toBytes()));
        settings.put("machines.eutxo.genesis.l2-key-epoch", "1");
        settings.put("machines.eutxo.validity.enabled", "true");
        settings.put("machines.eutxo.validity.provider",
                ZerojPoseidonValidityProvider.ID);
        settings.put("machines.eutxo.bridge.observer-id",
                "bridge-deposits");
        settings.put("machines.eutxo.bridge.vault-address",
                vaultAddress);
        settings.put("machines.eutxo.bridge.vault-script-hash",
                vaultScriptHash);
        settings.put("machines.eutxo.bridge.confirmation-observer-id",
                "bridge-withdrawals");
        settings.put("machines.eutxo.bridge.withdrawal-address",
                bob.address());
        settings.put("machines.eutxo.bridge.epoch", "0");
        settings.put("machines.eutxo.bridge.max-withdrawal-lovelace",
                "100");
        settings.put("machines.eutxo.bridge.max-pending-withdrawals",
                "4");
        settings.put("machines.eutxo.bridge.withdrawals-paused",
                "false");

        AppStateMachine first = new EutxoStateMachineProvider()
                .create(context(settings));
        AppStateMachine replay = new EutxoStateMachineProvider()
                .create(context(settings));
        MemoryAppState firstState = new MemoryAppState();
        MemoryAppState replayState = new MemoryAppState();
        AppBlock genesis = block(1);
        apply(first, genesis, firstState);
        apply(replay, genesis, replayState);

        TransactionOutput mirrored = TransactionOutput.builder()
                .address(alice.address())
                .value(Value.fromCoin(BigInteger.valueOf(100)))
                .build();
        byte[] outputCbor = CborSerializationUtil.serialize(
                mirrored.serialize());
        var accepted = new EutxoOutpoint("22".repeat(32), 0);
        EutxoDepositClaim claim = new EutxoDepositClaim(
                EutxoDepositClaim.ABI_VERSION,
                "eutxo-zk-test",
                accepted,
                10,
                new byte[32],
                vaultAddress,
                vaultScriptHash,
                outputCbor,
                alice.address(),
                outputCbor,
                fill(32, 5),
                new EutxoOutpoint("33".repeat(32), 0),
                100);
        L1Observation observation = new L1Observation(
                "bridge-deposits",
                HexFormat.of().parseHex(accepted.transactionId()),
                10,
                new byte[32],
                claim.encode());
        AppBlock depositBlock = block(
                2, observationMessage(8, observation));
        apply(first, depositBlock, firstState);
        apply(replay, depositBlock, replayState);

        long expiry = 100;
        EutxoWithdrawalDatum withdrawal = new EutxoWithdrawalDatum(
                EutxoWithdrawalDatum.ABI_VERSION,
                "eutxo-zk-test",
                0,
                bob.address(),
                fill(32, 6));
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(TransactionInput.builder()
                        .transactionId(claim.mirroredOutpoint()
                                .transactionId())
                        .index(claim.mirroredOutpoint().index())
                        .build()))
                .outputs(List.of(
                        TransactionOutput.builder()
                                .address(alice.address())
                                .value(Value.fromCoin(
                                        BigInteger.valueOf(70)))
                                .build(),
                        TransactionOutput.builder()
                                .address(bob.address())
                                .value(Value.fromCoin(
                                        BigInteger.valueOf(30)))
                                .inlineDatum(PlutusData.deserialize(
                                        withdrawal.encode()))
                                .build()))
                .fee(BigInteger.ZERO)
                .ttl(expiry)
                .networkId(NetworkId.TESTNET)
                .build();
        var authorization =
                EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1;
        EutxoL2Domain domain = new EutxoL2Domain(
                "eutxo-zk-test",
                "devnet",
                EutxoProfile.V1.digestHex(),
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex(),
                authorization.id(),
                authorization.digestHex(),
                fill(32, 7),
                expiry);
        EutxoL2Transaction transaction = l2Transaction(
                body, domain, alice, l2Secret);
        AppBlock withdrawalBlock = block(
                3, message(transaction.canonicalBytes()));
        apply(first, withdrawalBlock, firstState);
        apply(replay, withdrawalBlock, replayState);

        EutxoValidityTransition transition =
                EutxoValidityTransition.decode(firstState.get(
                        EutxoStateKeys.validityTransition(3, 0))
                        .orElseThrow());
        assertThat(transition.withdrawals()).singleElement()
                .satisfies(created -> {
                    assertThat(created.lovelace())
                            .isEqualTo(BigInteger.valueOf(30));
                    assertThat(created.destinationAddress())
                            .isEqualTo(bob.address());
                });
        assertThat(transition.resolvedInputs().getFirst().origin())
                .isEqualTo(EutxoRecord.Origin.L1_DEPOSIT);
        EutxoWithdrawalClaim claimFromTransition =
                transition.withdrawals().getFirst();
        EutxoWithdrawalClaim wrongAmount =
                new EutxoWithdrawalClaim(
                        claimFromTransition.abiVersion(),
                        claimFromTransition.chainId(),
                        claimFromTransition.bridgeEpoch(),
                        claimFromTransition.withdrawalOutpoint(),
                        claimFromTransition.destinationAddress(),
                        claimFromTransition.lovelace()
                                .add(BigInteger.ONE),
                        claimFromTransition.nonce(),
                        claimFromTransition.settlementSequence(),
                        claimFromTransition.requestedHeight());
        assertThatThrownBy(() -> new EutxoValidityTransition(
                transition.previousRoot(),
                transition.chainId(),
                transition.network(),
                transition.profileDigest(),
                transition.validityProfileDigest(),
                transition.authorizationProfile(),
                transition.authorizationProfileDigest(),
                transition.domainCommitment(),
                transition.transactionId(),
                transition.canonicalTransaction(),
                transition.resolvedInputs(),
                transition.consumed(),
                transition.created(),
                List.of(wrongAmount),
                transition.l1Slot(),
                transition.appHeight(),
                transition.ordinal()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lovelace-only output");
        assertThat(firstState.sameState(replayState)).isTrue();
        assertThat(firstState.stateRoot())
                .isEqualTo(replayState.stateRoot());
    }

    @Test
    void selectedOptionalProviderMaintainsAtomicSecondRoot() {
        EutxoTestWallet alice = wallet(1);
        EutxoTestWallet bob = wallet(2);
        Map<String, String> settings = new java.util.LinkedHashMap<>(
                ZerojPoseidonValidityProvider.requiredIdentitySettings());
        settings.put("machines.eutxo.profile", "yano-eutxo-v1");
        settings.put("machines.eutxo.genesis.address", alice.address());
        settings.put("machines.eutxo.genesis.lovelace", "100");
        settings.put("machines.eutxo.network", "devnet");
        settings.put("machines.eutxo.validity.enabled", "true");
        settings.put("machines.eutxo.validity.provider",
                ZerojPoseidonValidityProvider.ID);
        BigInteger aliceL2Secret = BigInteger.valueOf(101);
        var aliceL2Key = EdDSAJubjub.keypairFromSecret(aliceL2Secret);
        settings.put("machines.eutxo.genesis.l2-public-key",
                HexFormat.of().formatHex(aliceL2Key.pk().toBytes()));
        settings.put("machines.eutxo.genesis.l2-key-epoch", "1");
        AppStateMachine machine = new EutxoStateMachineProvider()
                .create(context(settings));
        MemoryAppState state = new MemoryAppState();

        apply(machine, block(1), state);
        byte[] genesisRoot = state.get(EutxoStateKeys.validityRoot())
                .orElseThrow();
        var genesis = EutxoQueryCodec.decodeRecords(machine.query(
                EutxoQueryCodec.ADDRESS_PATH,
                EutxoQueryCodec.addressRequest(alice.address()),
                state)).getFirst();
        var noDomain = EutxoTransactionFixtures.signedPayment(
                genesis.outpoint(),
                alice,
                List.of(new EutxoTransactionFixtures.Payment(
                        bob.address(), BigInteger.valueOf(100))),
                0,
                100);
        AppStateMachine.AdmissionResult noDomainAdmission =
                machine.validate(message(
                        EutxoTransactionFixtures.serialize(noDomain)));
        assertThat(noDomainAdmission.isAccepted()).isFalse();
        assertThat(noDomainAdmission.reason())
                .contains("INVALID_L2_TRANSACTION");

        byte[] nonce = new byte[32];
        Arrays.fill(nonce, (byte) 9);
        var authorizationProfile =
                com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts
                        .EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1;
        EutxoL2Domain domain = new EutxoL2Domain(
                "eutxo-zk-test",
                "devnet",
                EutxoProfile.V1.digestHex(),
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex(),
                authorizationProfile.id(),
                authorizationProfile.digestHex(),
                nonce,
                100);
        var cardanoBody = EutxoTransactionFixtures.signedPayment(
                genesis.outpoint(),
                alice,
                List.of(new EutxoTransactionFixtures.Payment(
                        bob.address(), BigInteger.valueOf(100))),
                0,
                100).getBody();

        EutxoL2Transaction payment = l2Transaction(
                cardanoBody, domain, alice, aliceL2Secret);
        byte[] transactionCbor = payment.canonicalBytes();
        apply(machine, block(2, message(transactionCbor)), state);

        byte[] nextRoot = state.get(EutxoStateKeys.validityRoot())
                .orElseThrow();
        EutxoValidityWitness witness = EutxoValidityWitness.decode(
                state.get(EutxoStateKeys.validityWitness()).orElseThrow());
        assertThat(nextRoot).isNotEqualTo(genesisRoot);
        assertThat(witness.previousRoot()).isEqualTo(genesisRoot);
        assertThat(witness.nextRoot()).isEqualTo(nextRoot);
        assertThat(witness.appHeight()).isEqualTo(2);
        EutxoValidityTransition transition = EutxoValidityTransition.decode(
                state.get(EutxoStateKeys.validityTransition(2, 0))
                        .orElseThrow());
        EutxoValidityTransition queried =
                EutxoQueryCodec.decodeOptionalValidityTransition(
                        machine.query(
                                EutxoQueryCodec.VALIDITY_TRANSITION_PATH,
                                EutxoQueryCodec.validityTransitionRequest(2, 0),
                                state));
        assertThat(transition.chainId()).isEqualTo("eutxo-zk-test");
        assertThat(transition.network()).isEqualTo("devnet");
        assertThat(transition.profileDigest())
                .isEqualTo("2499d01ee7cb0d09d0d498040c6351accd9da83df31666cd4463d0b1722d1212");
        assertThat(transition.validityProfileDigest())
                .isEqualTo(EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex());
        assertThat(transition.domainCommitment())
                .isEqualTo(domain.commitment());
        assertThat(transition.canonicalTransaction())
                .isEqualTo(transactionCbor);
        assertThat(transition.resolvedInputs())
                .containsExactly(genesis);
        assertThat(transition.digest()).isEqualTo(witness.transitionDigest());
        EutxoFinalizedProofWitness proofWitness =
                EutxoFinalizedProofWitness.derive(transition);
        assertThat(HexFormat.of().formatHex(
                proofWitness.signingCommitment()))
                .isEqualTo(transition.transactionId());
        assertThat(proofWitness.domain()).isEqualTo(domain);
        assertThat(proofWitness.authorizations()).hasSize(1);
        assertThat(proofWitness.authorizations().getFirst().inputIndexes())
                .containsExactly(0);
        assertThat(proofWitness.resolvedInputs())
                .containsExactly(genesis);
        assertThat(proofWitness.createdOutputs())
                .isEqualTo(transition.created());
        assertThat(proofWitness.validityStart()).isZero();
        assertThat(proofWitness.expiry()).isEqualTo(100);
        assertThat(proofWitness.canonicalBytes())
                .isEqualTo(transition.canonicalBytes());
        assertThat(EutxoValidityTransition.decode(
                transition.canonicalBytes())).isEqualTo(transition);
        assertThat(queried).isEqualTo(transition);
        EutxoL2ParameterSnapshot parameters =
                EutxoQueryCodec.decodeL2Parameters(machine.query(
                        EutxoQueryCodec.L2_PARAMETERS_PATH,
                        new byte[0],
                        state));
        assertThat(parameters.chainId()).isEqualTo("eutxo-zk-test");
        assertThat(parameters.ledgerProfileDigest())
                .isEqualTo(transition.profileDigest());
        assertThat(parameters.validityProfileDigest())
                .isEqualTo(transition.validityProfileDigest());
        assertThat(parameters.authorizationProfile())
                .isEqualTo(transition.authorizationProfile());
        assertThat(parameters.authorizationProfileDigest())
                .isEqualTo(transition.authorizationProfileDigest());
        assertThatThrownBy(() -> new EutxoValidityTransition(
                transition.previousRoot(),
                transition.chainId(),
                "preview",
                transition.profileDigest(),
                transition.validityProfileDigest(),
                transition.authorizationProfile(),
                transition.authorizationProfileDigest(),
                transition.domainCommitment(),
                transition.transactionId(),
                transition.canonicalTransaction(),
                transition.resolvedInputs(),
                transition.consumed(),
                transition.created(),
                transition.withdrawals(),
                transition.l1Slot(),
                transition.appHeight(),
                transition.ordinal()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("domain");
        byte[] wrongDomain = transition.domainCommitment();
        wrongDomain[0] ^= 1;
        assertThatThrownBy(() -> new EutxoValidityTransition(
                transition.previousRoot(),
                transition.chainId(),
                transition.network(),
                transition.profileDigest(),
                transition.validityProfileDigest(),
                transition.authorizationProfile(),
                transition.authorizationProfileDigest(),
                wrongDomain,
                transition.transactionId(),
                transition.canonicalTransaction(),
                transition.resolvedInputs(),
                transition.consumed(),
                transition.created(),
                transition.withdrawals(),
                transition.l1Slot(),
                transition.appHeight(),
                transition.ordinal()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("domain commitment");
        byte[] mutatedS = payment.authorizations().getFirst().s();
        mutatedS[0] ^= 1;
        EutxoL2Authorization mutatedAuthorization =
                new EutxoL2Authorization(
                        payment.authorizations().getFirst()
                                .paymentCredential(),
                        payment.authorizations().getFirst().keyEpoch(),
                        payment.authorizations().getFirst().publicKey(),
                        payment.authorizations().getFirst().rPoint(),
                        mutatedS,
                        payment.authorizations().getFirst().inputIndexes());
        EutxoL2Transaction mutatedEnvelope = new EutxoL2Transaction(
                payment.domain(), payment.transactionBody(),
                List.of(mutatedAuthorization));
        EutxoValidityTransition mutatedTransition =
                new EutxoValidityTransition(
                transition.previousRoot(),
                transition.chainId(),
                transition.network(),
                transition.profileDigest(),
                transition.validityProfileDigest(),
                transition.authorizationProfile(),
                transition.authorizationProfileDigest(),
                transition.domainCommitment(),
                transition.transactionId(),
                mutatedEnvelope.canonicalBytes(),
                transition.resolvedInputs(),
                transition.consumed(),
                transition.created(),
                transition.withdrawals(),
                transition.l1Slot(),
                transition.appHeight(),
                transition.ordinal());
        assertThat(mutatedTransition.digest())
                .isNotEqualTo(transition.digest());
        assertThat(EutxoQueryCodec.decodeOptionalValidityTransition(
                machine.query(
                        EutxoQueryCodec.VALIDITY_TRANSITION_PATH,
                        EutxoQueryCodec.validityTransitionRequest(3, 0),
                        state))).isNull();
        assertThat(state.get(EutxoStateKeys.validityEngine())
                .map(value -> new String(value, StandardCharsets.UTF_8)))
                .contains(ZerojPoseidonValidityProvider.ID);
    }

    private static AppStateMachineContext context(Map<String, String> settings) {
        return new AppStateMachineContext() {
            @Override
            public String chainId() {
                return "eutxo-zk-test";
            }

            @Override
            public Map<String, String> settings() {
                return settings;
            }
        };
    }

    private static AppMessage message(byte[] body) {
        byte[] id = new byte[32];
        Arrays.fill(id, (byte) 7);
        return AppMessage.builder()
                .version(1)
                .messageId(id)
                .chainId("eutxo-zk-test")
                .topic(EutxoContract.TRANSACTION_TOPIC)
                .sender(new byte[32])
                .senderSeq(1)
                .expiresAt(Long.MAX_VALUE)
                .body(body)
                .authScheme(0)
                .authProof(new byte[64])
                .build();
    }

    private static AppMessage observationMessage(
            int idByte,
            L1Observation observation
    ) {
        byte[] id = new byte[32];
        Arrays.fill(id, (byte) idByte);
        return AppMessage.builder()
                .version(1)
                .messageId(id)
                .chainId("eutxo-zk-test")
                .topic(observation.topic())
                .sender(new byte[32])
                .senderSeq(idByte)
                .expiresAt(Long.MAX_VALUE)
                .body(observation.encode())
                .authScheme(0)
                .authProof(new byte[64])
                .build();
    }

    private static byte[] fill(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static AppBlock block(long height, AppMessage... messages) {
        return new AppBlock(
                AppBlock.BLOCK_VERSION,
                "eutxo-zk-test",
                height,
                new byte[32],
                0,
                new byte[0],
                height,
                new byte[32],
                new byte[32],
                List.of(messages),
                new byte[32],
                FinalityCert.empty());
    }

    private static void apply(
            AppStateMachine machine,
            AppBlock block,
            MemoryAppState state
    ) {
        machine.apply(
                AppBlockExecutionContext.fromValidatedBlock(block),
                state,
                AppEffectEmitter.rejecting("effects are not expected"));
    }

    private static EutxoTestWallet wallet(int value) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) value);
        return EutxoTestWallet.fromSeed(seed);
    }

    private static EutxoL2Transaction l2Transaction(
            com.bloxbean.cardano.client.transaction.spec.TransactionBody body,
            EutxoL2Domain domain,
            EutxoTestWallet owner,
            BigInteger secret
    ) {
        try {
            byte[] bodyCbor =
                    com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
                            .serialize(body.serialize());
            String credential = new com.bloxbean.cardano.client.address.Address(
                    owner.address()).getPaymentCredentialHash()
                    .map(HexFormat.of()::formatHex)
                    .orElseThrow();
            var keypair = EdDSAJubjub.keypairFromSecret(secret);
            EutxoL2Authorization unsigned = new EutxoL2Authorization(
                    credential, 1, keypair.pk().toBytes(),
                    new byte[32], new byte[32], List.of(0));
            EutxoL2Transaction template = new EutxoL2Transaction(
                    domain, bodyCbor, List.of(unsigned));
            BigInteger message = new BigInteger(
                    1, template.signingCommitment()).mod(
                    com.bloxbean.cardano.zeroj.circuit.lib.jubjub
                            .JubjubCurve.BASE_FIELD_PRIME);
            EdDSAJubjub.Signature signature =
                    EdDSAJubjub.sign(secret, message);
            EutxoL2Authorization signed = new EutxoL2Authorization(
                    credential, 1, keypair.pk().toBytes(),
                    signature.r().toBytes(),
                    littleEndian32(signature.s()),
                    List.of(0));
            return new EutxoL2Transaction(
                    domain, bodyCbor, List.of(signed));
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "cannot build L2 test transaction", failure);
        }
    }

    private static byte[] littleEndian32(BigInteger value) {
        if (value.signum() < 0
                || value.compareTo(JubjubCurve.SUBGROUP_ORDER) >= 0) {
            throw new IllegalArgumentException("invalid Jubjub scalar");
        }
        byte[] bigEndian = value.toByteArray();
        int start = bigEndian.length > 1 && bigEndian[0] == 0 ? 1 : 0;
        byte[] result = new byte[32];
        for (int index = start; index < bigEndian.length; index++) {
            result[bigEndian.length - 1 - index] = bigEndian[index];
        }
        return result;
    }
}
