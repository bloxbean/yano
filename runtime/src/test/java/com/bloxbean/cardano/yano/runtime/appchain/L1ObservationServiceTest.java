package com.bloxbean.cardano.yano.runtime.appchain;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 observation recomputation + verification-window semantics (ADR 008.4
 * I3.2): built-in observers are deterministic, and verification mirrors the
 * I1.3 l1-ref verdicts (OK / MISMATCH fail-closed / AHEAD / UNKNOWN).
 */
class L1ObservationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(L1ObservationServiceTest.class);
    private static final String WATCHED = "addr_test1qwatched";

    private L1ObservationService service() {
        return L1ObservationService.fromConfig(Map.of(
                "observers.deposits.type", "address-deposit",
                "observers.deposits.address", WATCHED,
                "observers.registry.type", "metadata-label",
                "observers.registry.label", "7014"), 128, null, log);
    }

    private static byte[] fill(int len, int b) {
        byte[] bytes = new byte[len];
        java.util.Arrays.fill(bytes, (byte) b);
        return bytes;
    }

    private static String metadataCborHex(long label, String value) throws Exception {
        co.nstant.in.cbor.model.Map map = new co.nstant.in.cbor.model.Map();
        map.put(new UnsignedInteger(BigInteger.valueOf(label)), new UnicodeString(value));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CborEncoder(out).encode(map);
        return HexUtil.encodeHexString(out.toByteArray());
    }

    private Block block(String txHash, String toAddress, long lovelace, String metadataHex) {
        TransactionBody tx = TransactionBody.builder()
                .txHash(txHash)
                .outputs(List.of(TransactionOutput.builder()
                        .address(toAddress)
                        .amounts(List.of(Amount.builder()
                                .unit("lovelace")
                                .quantity(BigInteger.valueOf(lovelace))
                                .build()))
                        .build()))
                .build();
        Block.BlockBuilder builder = Block.builder().transactionBodies(List.of(tx));
        if (metadataHex != null) {
            builder.auxiliaryDataMap(Map.of(0, new AuxData(metadataHex, null, null, null, null, null)));
        }
        return builder.build();
    }

    private AppMessage message(L1Observation observation) {
        return AppMessage.builder()
                .messageId(new byte[32])
                .chainId("test-chain")
                .topic(observation.topic())
                .sender(new byte[32])
                .body(observation.encode())
                .build();
    }

    @Test
    void observers_computeDepositAndMetadataClaims() throws Exception {
        L1ObservationService service = service();
        Block block = block("aa".repeat(32), WATCHED, 5_000_000, metadataCborHex(7014, "hello"));

        service.onL1Block(100, fill(32, 1), block);
        List<L1Observation> observed = service.drainInjectable(100);

        assertThat(observed).hasSize(2);
        assertThat(observed).extracting(L1Observation::observerId)
                .containsExactlyInAnyOrder("deposits", "registry");
        for (L1Observation observation : observed) {
            assertThat(observation.slot()).isEqualTo(100);
            assertThat(observation.txHash()).isEqualTo(HexUtil.decodeHexString("aa".repeat(32)));
            // Codec round-trip
            L1Observation decoded = L1Observation.decode(observation.encode());
            assertThat(decoded).isNotNull();
            assertThat(decoded.key()).isEqualTo(observation.key());
            assertThat(decoded.claim()).isEqualTo(observation.claim());
        }
    }

    @Test
    void unwatchedBlock_yieldsNothing() {
        L1ObservationService service = service();
        service.onL1Block(100, fill(32, 1),
                block("bb".repeat(32), "addr_test1qother", 5_000_000, null));
        assertThat(service.drainInjectable(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void injection_isStabilityGated_andRollbackDiscardsPending() {
        // Rollback safety: a watched fact must NOT drain before the stable
        // ref reaches its slot, and a rollback before that discards it
        L1ObservationService service = service();
        service.onL1Block(100, fill(32, 1), block("aa".repeat(32), WATCHED, 5_000_000, null));

        assertThat(service.drainInjectable(99)).isEmpty();      // not stable yet
        service.onL1Rollback(90);                                // fact reorged away
        assertThat(service.drainInjectable(100)).isEmpty();     // never injected

        // Re-observed on the new chain at a different slot → drains once stable
        service.onL1Block(105, fill(32, 2), block("aa".repeat(32), WATCHED, 5_000_000, null));
        assertThat(service.drainInjectable(104)).isEmpty();
        List<L1Observation> ready = service.drainInjectable(105);
        assertThat(ready).hasSize(1);
        assertThat(ready.get(0).slot()).isEqualTo(105);
        // Drain is remove-once (single injection attempt)
        assertThat(service.drainInjectable(105)).isEmpty();
    }

    @Test
    void verify_fullVerdictMatrix() throws Exception {
        // Two members observing the same L1 stream compute identical claims
        L1ObservationService proposer = service();
        L1ObservationService follower = service();
        Block block = block("aa".repeat(32), WATCHED, 5_000_000, metadataCborHex(7014, "hello"));
        proposer.onL1Block(100, fill(32, 1), block);
        List<L1Observation> observed = proposer.drainInjectable(100);
        follower.onL1Block(100, fill(32, 1), block);
        follower.onL1Block(110, fill(32, 2), block("cc".repeat(32), "addr_other", 1, null));

        L1Observation deposit = observed.stream()
                .filter(o -> o.observerId().equals("deposits")).findFirst().orElseThrow();

        // OK: matches own recomputation
        assertThat(follower.verify(message(deposit)))
                .isEqualTo(AppChainEngine.L1RefVerdict.OK);

        // MISMATCH: tampered claim (fail-closed)
        L1Observation tampered = new L1Observation(deposit.observerId(), deposit.txHash(),
                deposit.slot(), deposit.blockHash(), new byte[]{0x00});
        assertThat(follower.verify(message(tampered)))
                .isEqualTo(AppChainEngine.L1RefVerdict.MISMATCH);

        // MISMATCH: wrong L1 block hash at that slot
        L1Observation wrongBlock = new L1Observation(deposit.observerId(), deposit.txHash(),
                deposit.slot(), fill(32, 9), deposit.claim());
        assertThat(follower.verify(message(wrongBlock)))
                .isEqualTo(AppChainEngine.L1RefVerdict.MISMATCH);

        // MISMATCH: fabricated observation at an in-window slot we saw
        L1Observation fabricated = new L1Observation("deposits", fill(32, 7), 110,
                fill(32, 2), deposit.claim());
        assertThat(follower.verify(message(fabricated)))
                .isEqualTo(AppChainEngine.L1RefVerdict.MISMATCH);

        // AHEAD: newer than our L1 view
        L1Observation ahead = new L1Observation(deposit.observerId(), deposit.txHash(),
                999, deposit.blockHash(), deposit.claim());
        assertThat(follower.verify(message(ahead)))
                .isEqualTo(AppChainEngine.L1RefVerdict.AHEAD);

        // MISMATCH: topic/body disagreement (fail-closed)
        AppMessage wrongTopic = AppMessage.builder()
                .messageId(new byte[32]).chainId("test-chain")
                .topic("~l1/other").sender(new byte[32]).body(deposit.encode()).build();
        assertThat(follower.verify(wrongTopic))
                .isEqualTo(AppChainEngine.L1RefVerdict.MISMATCH);

        // MISMATCH: undecodable body
        AppMessage garbage = AppMessage.builder()
                .messageId(new byte[32]).chainId("test-chain")
                .topic("~l1/deposits").sender(new byte[32]).body(new byte[]{0x01}).build();
        assertThat(follower.verify(garbage))
                .isEqualTo(AppChainEngine.L1RefVerdict.MISMATCH);
    }

    @Test
    void verify_belowWindowIsUnknown_andRollbackForgets() throws Exception {
        L1ObservationService follower = service();
        Block block = block("aa".repeat(32), WATCHED, 5_000_000, null);
        follower.onL1Block(100, fill(32, 1), block);
        L1Observation deposit = follower.drainInjectable(100).get(0);

        // Window advances far past slot 100 (window size 128 blocks)
        for (int i = 0; i < 200; i++) {
            follower.onL1Block(200 + i, fill(32, 3), block("dd".repeat(32), "addr_x", 1, null));
        }
        assertThat(follower.verify(message(deposit)))
                .isEqualTo(AppChainEngine.L1RefVerdict.UNKNOWN);

        // Rollback below an observed slot: the observation is forgotten and
        // the slot is now AHEAD of the rolled-back view
        follower.onL1Rollback(150);
        L1Observation later = new L1Observation("deposits", fill(32, 5), 300,
                fill(32, 3), deposit.claim());
        assertThat(follower.verify(message(later)))
                .isEqualTo(AppChainEngine.L1RefVerdict.AHEAD);
    }

    @Test
    void misconfiguredObserver_failsFast() {
        assertThatThrownBy(() -> L1ObservationService.fromConfig(Map.of(
                "observers.x.type", "no-such-type"), 128, null, log))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown L1 observer type");
        assertThatThrownBy(() -> L1ObservationService.fromConfig(Map.of(
                "observers.x.type", "address-deposit"), 128, null, log))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("address is required");
        assertThat(L1ObservationService.fromConfig(Map.of("sinks.a.b", "c"), 128, null, log))
                .isNull();
    }
}
