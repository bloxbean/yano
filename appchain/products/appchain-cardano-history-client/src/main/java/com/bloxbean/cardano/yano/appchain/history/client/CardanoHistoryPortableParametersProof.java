package com.bloxbean.cardano.yano.appchain.history.client;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.ProofSubjects;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.bloxbean.cardano.yano.api.appchain.l1view.ProtocolParamsCanonicalCodec;

import java.util.Arrays;
import java.util.HexFormat;

/** JSON-safe protocol-parameters proof with a separately verifiable root binding. */
public record CardanoHistoryPortableParametersProof(
        int schemaVersion,
        long epoch,
        String componentId,
        AppChainClient.Proof proof,
        CardanoHistoryTrustedRoot trustedRoot
) {
    public CardanoHistoryPortableParametersProof {
        if (schemaVersion != 1 || epoch < 0 || componentId == null || componentId.isBlank()
                || proof == null || trustedRoot == null) {
            throw new IllegalArgumentException("invalid portable parameters proof");
        }
    }

    public static CardanoHistoryPortableParametersProof from(
            CardanoHistoryProofBundle.ProtocolParameters value,
            ProofVerifier.TrustedStateRoot root) {
        return new CardanoHistoryPortableParametersProof(1, value.epoch(), value.componentId(),
                value.fact().proof(), CardanoHistoryTrustedRoot.from(root));
    }

    public Verification verify() {
        try {
            ProofVerifier.TrustedStateRoot embedded = trustedRoot.toVerifierRoot();
            return verify(new ProofVerifier.TrustedStateRoot(embedded.chainId(), embedded.profile(),
                    embedded.genesisIdHex(), embedded.height(), embedded.stateRootHex(),
                    ProofVerifier.TrustedRootSource.CALLER_PINNED, embedded.blockHashHex()));
        } catch (RuntimeException malformed) {
            return Verification.INVALID;
        }
    }

    public Verification verify(ProofVerifier.TrustedStateRoot independentlyTrustedRoot) {
        try {
            var subject = ProofSubjects.epochProtocolParameters(componentId, epoch);
            if (proof.presence() != AppChainClient.ProofPresence.PRESENT
                    || proof.valueHex() == null
                    || !Arrays.equals(HexFormat.of().parseHex(proof.keyHex()), subject.canonicalKey())
                    || !ProofVerifier.verify(proof, independentlyTrustedRoot)) return Verification.INVALID;
            byte[] value = HexFormat.of().parseHex(proof.valueHex());
            if (!Arrays.equals(value, ProtocolParamsCanonicalCodec.validate(epoch, value))) {
                return Verification.INVALID;
            }
            return independentlyTrustedRoot.source() == ProofVerifier.TrustedRootSource.CARDANO_ANCHOR
                    ? Verification.L1_ANCHORED_VALID
                    : Verification.ROOT_VERIFIED_ANCHOR_UNCHECKED;
        } catch (RuntimeException malformed) {
            return Verification.INVALID;
        }
    }

    public enum Verification { INVALID, ROOT_VERIFIED_ANCHOR_UNCHECKED, L1_ANCHORED_VALID }
}
