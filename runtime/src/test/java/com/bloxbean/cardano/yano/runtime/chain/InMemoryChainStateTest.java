package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryChainStateTest {

    @Test
    void rollbackPreservesBodyTipWhenHeaderTipWasAhead() {
        InMemoryChainState chainState = new InMemoryChainState();
        byte[] bodyHash = hash(1);
        byte[] headerHash = hash(2);

        chainState.storeBlock(bodyHash, 1L, 10L, new byte[]{1});
        chainState.storeBlockHeader(headerHash, 2L, 20L, new byte[]{2});

        chainState.rollbackTo(15L);

        assertNotNull(chainState.getTip());
        assertEquals(10L, chainState.getTip().getSlot());
        assertEquals(1L, chainState.getTip().getBlockNumber());
        assertNotNull(chainState.getHeaderTip());
        assertEquals(10L, chainState.getHeaderTip().getSlot());
        assertNull(chainState.getBlockHeader(headerHash));
    }

    @Test
    void byronEbHeaderDoesNotOverwriteMainHeaderNumberIndex() {
        InMemoryChainState chainState = new InMemoryChainState();
        byte[] mainHash = hash(1);
        byte[] ebbHash = hash(2);

        chainState.storeBlockHeader(mainHash, 5L, 50L, new byte[]{1});
        chainState.storeByronEbHeader(ebbHash, 5L, 40L, new byte[]{2});

        assertArrayEquals(new byte[]{1}, chainState.getBlockHeaderByNumber(5L));
        assertArrayEquals(new byte[]{2}, chainState.getBlockHeader(ebbHash));
    }

    private static byte[] hash(int value) {
        return HexUtil.decodeHexString(String.format("%064x", value));
    }
}
