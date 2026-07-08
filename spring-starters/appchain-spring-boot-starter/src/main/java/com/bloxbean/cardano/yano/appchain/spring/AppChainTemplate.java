package com.bloxbean.cardano.yano.appchain.spring;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.Hex;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;

/**
 * Spring-friendly facade over {@link AppChainClient} (ADR app-layer/006 E1.4):
 * submit, read, and <em>verify</em> app-chain records with one injected bean.
 * For anything not covered here, {@link #client()} exposes the full SDK.
 */
public class AppChainTemplate {

    private final AppChainClient client;

    public AppChainTemplate(AppChainClient client) {
        this.client = client;
    }

    /** The underlying SDK client. */
    public AppChainClient client() {
        return client;
    }

    /** Submit a UTF-8 text body; returns the message id (hex). */
    public String send(String topic, String body) {
        return client.submitText(topic, body).messageId();
    }

    /** Submit an opaque body; returns the message id (hex). */
    public String send(String topic, byte[] body) {
        return client.submit(topic, body).messageId();
    }

    /** Submit a typed payload via an encoder (e.g. {@code codec::encode}). */
    public <T> String send(String topic, T payload, Function<T, byte[]> encoder) {
        return client.submitTyped(topic, payload, encoder).messageId();
    }

    /** Current tip (height + state root). */
    public AppChainClient.Tip tip() {
        return client.tip();
    }

    /**
     * Fetch the inclusion proof for a state key and verify it locally
     * ("don't trust, verify"). Empty when the key has no committed entry.
     *
     * @return the verified proof, or empty when absent or INVALID
     */
    public Optional<AppChainClient.Proof> verifiedProof(byte[] stateKey) {
        return client.proof(stateKey).filter(ProofVerifier::verify);
    }

    /** Convenience: verified proof for a UTF-8 string key. */
    public Optional<AppChainClient.Proof> verifiedProof(String stateKey) {
        return verifiedProof(stateKey.getBytes(StandardCharsets.UTF_8));
    }

    /** Verified proof for a finalized message id (hex). */
    public Optional<AppChainClient.Proof> verifiedMessageProof(String messageIdHex) {
        return verifiedProof(Hex.decode(messageIdHex));
    }
}
