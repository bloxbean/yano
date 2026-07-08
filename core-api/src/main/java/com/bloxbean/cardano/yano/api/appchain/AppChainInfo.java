package com.bloxbean.cardano.yano.api.appchain;

/**
 * Static facts about the chain, handed to {@link AppStateMachine#init}.
 *
 * @param chainId        app-chain identity
 * @param memberKeyHex   this node's member public key (hex)
 * @param membersCount   size of the membership registry
 */
public record AppChainInfo(String chainId, String memberKeyHex, int membersCount) {
}
