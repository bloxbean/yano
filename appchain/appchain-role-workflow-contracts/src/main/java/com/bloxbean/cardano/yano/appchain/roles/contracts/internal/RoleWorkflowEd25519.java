package com.bloxbean.cardano.yano.appchain.roles.contracts.internal;

import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowException;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowResultCode;

import java.security.KeyFactory;
import java.security.Provider;
import java.security.Security;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/** Pinned, strict and total Ed25519 profile for the frozen role-workflow v1 contract. */
public final class RoleWorkflowEd25519 {
    private static final Provider STRICT_PROVIDER = strictProvider();
    private static final byte[] X509_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };
    private static final SigningProvider SECOND_VERIFIER = new EdDSASigningProvider();

    private RoleWorkflowEd25519() {
    }

    public static byte[] sign(byte[] message, byte[] privateSeed) {
        if (message == null || privateSeed == null || privateSeed.length != 32) {
            throw new RoleWorkflowException(RoleWorkflowResultCode.INVALID_SIGNATURE);
        }
        try {
            return SECOND_VERIFIER.sign(message.clone(), privateSeed.clone());
        } catch (RuntimeException failure) {
            throw new RoleWorkflowException(RoleWorkflowResultCode.INVALID_SIGNATURE);
        }
    }

    public static boolean verify(byte[] signature, byte[] message, byte[] publicKey) {
        if (signature == null || signature.length != 64 || message == null
                || publicKey == null || publicKey.length != 32) {
            return false;
        }
        try {
            byte[] encodedKey = new byte[X509_PREFIX.length + publicKey.length];
            System.arraycopy(X509_PREFIX, 0, encodedKey, 0, X509_PREFIX.length);
            System.arraycopy(publicKey, 0, encodedKey, X509_PREFIX.length, publicKey.length);
            var keyFactory = KeyFactory.getInstance("Ed25519", STRICT_PROVIDER);
            var strict = Signature.getInstance("Ed25519", STRICT_PROVIDER);
            strict.initVerify(keyFactory.generatePublic(new X509EncodedKeySpec(encodedKey)));
            strict.update(message);
            return strict.verify(signature)
                    && SECOND_VERIFIER.verify(signature.clone(), message.clone(), publicKey.clone());
        } catch (Exception | LinkageError failure) {
            return false;
        }
    }

    private static Provider strictProvider() {
        Provider provider = Security.getProvider("SunEC");
        if (provider == null) {
            throw new IllegalStateException("required Ed25519 verifier is unavailable");
        }
        return provider;
    }
}
