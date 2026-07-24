package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityCommitment;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityCommitmentEngine;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityTransition;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoValidityWitness;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonHash;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonParamsBLS12_381T3;

import java.math.BigInteger;
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
}
