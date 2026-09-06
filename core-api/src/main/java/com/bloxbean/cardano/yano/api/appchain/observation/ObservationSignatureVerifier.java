package com.bloxbean.cardano.yano.api.appchain.observation;

/** Pure signature primitive supplied by the host to certificate verification. */
@FunctionalInterface
public interface ObservationSignatureVerifier {
    boolean verify(byte[] publicKey, byte[] signingDigest, byte[] signature);
}
