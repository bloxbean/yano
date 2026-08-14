package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProviderFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/** Harmless signer fixture used only by the native plugin conformance build. */
public final class ConformanceSignerProviderFactory implements SignerProviderFactory {
    public static final String SCHEME = "conformance-signer";

    @Override
    public String scheme() {
        return SCHEME;
    }

    @Override
    public SignerProvider create(String keyReference) {
        SignerProvider signer = new SignerProvider() {
            private final AtomicBoolean firstCallback = new AtomicBoolean(true);

            @Override
            public byte[] sign(byte[] message) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "signer signing");
                return new byte[64];
            }

            @Override
            public byte[] publicKey() {
                ConformanceTcclProbe.requireCatalogFacade("signer public key");
                ConformanceTcclProbe.productCallback(firstCallback,
                        "signer public key");
                return new byte[32];
            }
        };
        ConformanceTcclProbe.poisonProviderCallback();
        return signer;
    }
}
