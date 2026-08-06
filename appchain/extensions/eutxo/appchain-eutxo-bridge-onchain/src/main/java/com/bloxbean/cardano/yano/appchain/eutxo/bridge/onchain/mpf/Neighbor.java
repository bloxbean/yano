package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain.mpf;

import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

/**
 * A neighbor node used in a Merkle Patricia Forestry proof.
 */
@OnchainLibrary
public record Neighbor(int nibble, byte[] prefix, byte[] root) {}
