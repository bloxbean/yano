package com.bloxbean.cardano.yano.appchain.eutxo.zk.testkit;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoContract;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoStateKeys;
import com.bloxbean.cardano.yano.appchain.eutxo.ledger.EutxoStateMachineProvider;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTransactionFixtures;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.MemoryAppState;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoValidityWitness;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.ZerojPoseidonValidityProvider;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoValidityIntegrationTest {

    @Test
    void selectedOptionalProviderMaintainsAtomicSecondRoot() {
        EutxoTestWallet alice = wallet(1);
        EutxoTestWallet bob = wallet(2);
        Map<String, String> settings = new java.util.LinkedHashMap<>(
                ZerojPoseidonValidityProvider.requiredIdentitySettings());
        settings.put("machines.eutxo.profile", "yano-eutxo-v1");
        settings.put("machines.eutxo.genesis.address", alice.address());
        settings.put("machines.eutxo.genesis.lovelace", "100");
        settings.put("machines.eutxo.validity.enabled", "true");
        settings.put("machines.eutxo.validity.provider",
                ZerojPoseidonValidityProvider.ID);
        AppStateMachine machine = new EutxoStateMachineProvider()
                .create(context(settings));
        MemoryAppState state = new MemoryAppState();

        machine.apply(block(1), state);
        byte[] genesisRoot = state.get(EutxoStateKeys.validityRoot())
                .orElseThrow();
        var genesis = EutxoQueryCodec.decodeRecords(machine.query(
                EutxoQueryCodec.ADDRESS_PATH,
                EutxoQueryCodec.addressRequest(alice.address()),
                state)).getFirst();
        var payment = EutxoTransactionFixtures.signedPayment(
                genesis.outpoint(),
                alice,
                List.of(new EutxoTransactionFixtures.Payment(
                        bob.address(), BigInteger.valueOf(100))),
                0,
                0);

        machine.apply(block(2, message(
                EutxoTransactionFixtures.serialize(payment))), state);

        byte[] nextRoot = state.get(EutxoStateKeys.validityRoot())
                .orElseThrow();
        EutxoValidityWitness witness = EutxoValidityWitness.decode(
                state.get(EutxoStateKeys.validityWitness()).orElseThrow());
        assertThat(nextRoot).isNotEqualTo(genesisRoot);
        assertThat(witness.previousRoot()).isEqualTo(genesisRoot);
        assertThat(witness.nextRoot()).isEqualTo(nextRoot);
        assertThat(witness.appHeight()).isEqualTo(2);
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

    private static EutxoTestWallet wallet(int value) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) value);
        return EutxoTestWallet.fromSeed(seed);
    }
}
