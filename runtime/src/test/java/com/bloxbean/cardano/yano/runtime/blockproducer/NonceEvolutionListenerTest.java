package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.VrfCert;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NonceEvolutionListenerTest {

    @Test
    void storesEpochNonceOnEpochTransitionAndPrunesOnRollback() {
        EpochNonceState state = new EpochNonceState(10, 1, 1.0);
        state.initFromGenesisHash(bytes(32, 1));
        TestNonceStore store = new TestNonceStore();
        NonceEvolutionListener listener = new NonceEvolutionListener(state, store, null);

        listener.onBlockApplied(blockEvent(0, null, 2));
        listener.onBlockApplied(blockEvent(10, "aa".repeat(32), 3));

        assertThat(state.getCurrentEpoch()).isEqualTo(1);
        assertThat(store.getEpochNonce(1)).isEqualTo(state.getEpochNonce());

        listener.onRollback(new RollbackEvent(new Point(0, "bb".repeat(32)), true));

        assertThat(state.getCurrentEpoch()).isEqualTo(0);
        assertThat(store.getEpochNonce(1)).isNull();
    }

    private static BlockAppliedEvent blockEvent(long slot, String prevHash, int vrfSeed) {
        var vrf = VrfCert.builder()
                ._1(HexUtil.encodeHexString(bytes(64, vrfSeed)))
                .build();
        var headerBody = HeaderBody.builder()
                .slot(slot)
                .blockNumber(slot + 1)
                .prevHash(prevHash)
                .vrfResult(vrf)
                .build();
        var header = BlockHeader.builder()
                .headerBody(headerBody)
                .build();
        var block = Block.builder()
                .era(Era.Babbage)
                .header(header)
                .build();
        return new BlockAppliedEvent(Era.Babbage, slot, slot + 1, "cc".repeat(32), block);
    }

    private static byte[] bytes(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }

    private static final class TestNonceStore implements NonceStateStore {
        private byte[] state;
        private final Map<Integer, byte[]> nonces = new HashMap<>();

        @Override
        public void storeEpochNonceState(byte[] serialized) {
            this.state = serialized;
        }

        @Override
        public byte[] getEpochNonceState() {
            return state;
        }

        @Override
        public void storeEpochNonce(int epoch, byte[] nonce) {
            nonces.put(epoch, nonce != null ? nonce.clone() : null);
        }

        @Override
        public byte[] getEpochNonce(int epoch) {
            byte[] nonce = nonces.get(epoch);
            return nonce != null ? nonce.clone() : null;
        }

        @Override
        public void pruneEpochNoncesAfter(int epoch) {
            nonces.keySet().removeIf(storedEpoch -> storedEpoch > epoch);
        }
    }
}
