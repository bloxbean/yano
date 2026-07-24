package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Authorization;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2KeyRegistration;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityCommitment;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityCommitmentEngine;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityTransition;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoValidityWitness;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkAuthorizationProfile;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.EdDSAJubjub;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubCurve;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubPoint;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonHash;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonParamsBLS12_381T3;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic Poseidon accumulator used as the ZK-friendly second root.
 *
 * <p>This class performs no proving and no I/O. It is safe to invoke from the
 * deterministic state-machine apply path.</p>
 */
public final class ZerojPoseidonValidityEngine
        implements EutxoValidityCommitmentEngine {
    private final String chainId;
    private final EutxoProfile profile;
    private final byte[] genesisRoot;

    public ZerojPoseidonValidityEngine(String chainId, EutxoProfile profile) {
        this.chainId = Objects.requireNonNull(chainId, "chainId");
        this.profile = Objects.requireNonNull(profile, "profile");
        BigInteger root = poseidon(
                ZerojScalars.domain("yano:eutxo:chain:" + chainId),
                ZerojScalars.domain("yano:eutxo:profile:" + profile.digestHex()));
        this.genesisRoot = ZerojScalars.bytes32(root);
    }

    @Override
    public String id() {
        return ZerojPoseidonValidityProvider.ID;
    }

    @Override
    public String profileDigest() {
        return com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts
                .EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex();
    }

    @Override
    public String authorizationProfile() {
        return EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1.id();
    }

    @Override
    public String authorizationProfileDigest() {
        return EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1.digestHex();
    }

    @Override
    public AuthorizationResult verifyAuthorization(
            EutxoL2Transaction transaction,
            List<EutxoL2KeyRegistration> registrations
    ) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(registrations, "registrations");
        Map<String, EutxoL2KeyRegistration> byCredential =
                new LinkedHashMap<>();
        for (EutxoL2KeyRegistration registration : registrations) {
            if (byCredential.putIfAbsent(
                    registration.paymentCredential(), registration) != null) {
                return AuthorizationResult.reject(
                        "DUPLICATE_L2_KEY_REGISTRATION",
                        "a payment credential has multiple active registrations");
            }
        }
        BigInteger message = ZerojScalars.scalar(
                transaction.signingCommitment());
        for (EutxoL2Authorization authorization :
                transaction.authorizations()) {
            EutxoL2KeyRegistration registration =
                    byCredential.get(authorization.paymentCredential());
            if (registration == null) {
                return AuthorizationResult.reject(
                        "L2_KEY_NOT_REGISTERED",
                        "input payment credential has no registered L2 key");
            }
            if (registration.status()
                    != EutxoL2KeyRegistration.Status.ACTIVE) {
                return AuthorizationResult.reject(
                        "L2_KEY_REVOKED",
                        "registered L2 key is not active");
            }
            if (!authorizationProfile().equals(
                    registration.authorizationProfile())
                    || registration.keyEpoch() != authorization.keyEpoch()
                    || !Arrays.equals(
                    registration.publicKey(), authorization.publicKey())) {
                return AuthorizationResult.reject(
                        "L2_KEY_MISMATCH",
                        "authorization does not match the active L2 key epoch");
            }
            try {
                JubjubPoint publicKey = canonicalPoint(
                        authorization.publicKey(), "public key");
                JubjubPoint rPoint = canonicalPoint(
                        authorization.rPoint(), "signature R");
                if (publicKey.isIdentity() || rPoint.isIdentity()
                        || !publicKey.isInSubgroup()
                        || !rPoint.isInSubgroup()) {
                    return AuthorizationResult.reject(
                            "INVALID_JUBJUB_SUBGROUP",
                            "Jubjub points must be non-identity subgroup points");
                }
                BigInteger s = fromLittleEndian(authorization.s());
                if (s.signum() < 0
                        || s.compareTo(JubjubCurve.SUBGROUP_ORDER) >= 0
                        || !EdDSAJubjub.verify(
                        publicKey, message,
                        new EdDSAJubjub.Signature(rPoint, s))) {
                    return AuthorizationResult.reject(
                            "INVALID_JUBJUB_SIGNATURE",
                            "Jubjub authorization signature is invalid");
                }
            } catch (IllegalArgumentException failure) {
                return AuthorizationResult.reject(
                        "INVALID_JUBJUB_ENCODING",
                        "Jubjub authorization is not canonically encoded");
            }
        }
        if (byCredential.size() != transaction.authorizations().size()) {
            return AuthorizationResult.reject(
                    "UNRELATED_L2_KEY_REGISTRATION",
                    "resolved registrations differ from L2 authorizations");
        }
        return AuthorizationResult.accept();
    }

    @Override
    public EutxoValidityCommitment genesis() {
        EutxoValidityWitness witness = new EutxoValidityWitness(
                id(),
                genesisRoot,
                genesisRoot,
                new byte[32],
                "genesis",
                0,
                0);
        return new EutxoValidityCommitment(genesisRoot, witness.encode());
    }

    @Override
    public EutxoValidityCommitment commit(EutxoValidityTransition transition) {
        Objects.requireNonNull(transition, "transition");
        byte[] digest = transition.digest();
        byte[] nextRoot = ZerojScalars.bytes32(poseidon(
                ZerojScalars.scalar(transition.previousRoot()),
                ZerojScalars.scalar(digest)));
        EutxoValidityWitness witness = new EutxoValidityWitness(
                id(),
                transition.previousRoot(),
                nextRoot,
                digest,
                transition.transactionId(),
                transition.appHeight(),
                transition.ordinal());
        return new EutxoValidityCommitment(nextRoot, witness.encode());
    }

    public String chainId() {
        return chainId;
    }

    public EutxoProfile profile() {
        return profile;
    }

    private static BigInteger poseidon(BigInteger left, BigInteger right) {
        return PoseidonHash.hash(
                PoseidonParamsBLS12_381T3.INSTANCE, left, right);
    }

    private static JubjubPoint canonicalPoint(byte[] encoded, String label) {
        JubjubPoint point = JubjubPoint.fromBytes(encoded);
        if (!Arrays.equals(encoded, point.toBytes())) {
            throw new IllegalArgumentException("non-canonical " + label);
        }
        return point;
    }

    private static BigInteger fromLittleEndian(byte[] encoded) {
        byte[] reversed = encoded.clone();
        for (int left = 0, right = reversed.length - 1;
             left < right; left++, right--) {
            byte value = reversed[left];
            reversed[left] = reversed[right];
            reversed[right] = value;
        }
        return new BigInteger(1, reversed);
    }
}
