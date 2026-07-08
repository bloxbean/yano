package com.bloxbean.cardano.yano.api.appchain;

/**
 * A verified app message as seen by this node (local submission or peer diffusion).
 * The body is the application's opaque payload — the framework never interprets it.
 *
 * @param messageIdHex content-derived message id (blake2b-256, hex)
 * @param chainId      app-chain the message belongs to
 * @param topic        sub-stream within the chain ("" = default)
 * @param senderHex    sender's Ed25519 public key (hex)
 * @param senderSeq    per-sender sequence number
 * @param expiresAt    unix seconds expiry
 * @param body         opaque application payload
 * @param receivedAt   unix millis when this node accepted the message
 * @param source       how the message reached this node
 */
public record ReceivedAppMessage(String messageIdHex,
                                 String chainId,
                                 String topic,
                                 String senderHex,
                                 long senderSeq,
                                 long expiresAt,
                                 byte[] body,
                                 long receivedAt,
                                 Source source) {

    public enum Source {
        LOCAL,
        PEER
    }
}
