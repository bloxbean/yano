package com.bloxbean.cardano.yano.archive.core.address;

public record ResolvedOutput(byte[] addressKey, String address, byte[] paymentCredential,
                             String stakeCredentialType, byte[] stakeCredential) {
    public ResolvedOutput {
        addressKey = addressKey == null ? null : addressKey.clone();
        paymentCredential = paymentCredential == null ? null : paymentCredential.clone();
        stakeCredential = stakeCredential == null ? null : stakeCredential.clone();
    }
    public ResolvedOutput(byte[] addressKey, byte[] paymentCredential, byte[] stakeCredential) {
        this(addressKey, null, paymentCredential, stakeCredential == null ? null : "key", stakeCredential);
    }
    @Override public byte[] addressKey() { return addressKey == null ? null : addressKey.clone(); }
    @Override public byte[] paymentCredential() { return paymentCredential == null ? null : paymentCredential.clone(); }
    @Override public byte[] stakeCredential() { return stakeCredential == null ? null : stakeCredential.clone(); }
}
