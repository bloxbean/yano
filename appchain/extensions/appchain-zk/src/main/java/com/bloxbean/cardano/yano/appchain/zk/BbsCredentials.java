package com.bloxbean.cardano.yano.appchain.zk;

import com.bloxbean.cardano.zeroj.bbs.BbsCiphersuite;
import com.bloxbean.cardano.zeroj.bbs.BbsKeyPair;
import com.bloxbean.cardano.zeroj.bbs.BbsPresentation;
import com.bloxbean.cardano.zeroj.bbs.BbsPresentationCodec;
import com.bloxbean.cardano.zeroj.bbs.BbsPublicKey;
import com.bloxbean.cardano.zeroj.bbs.BbsService;
import com.bloxbean.cardano.zeroj.bbs.BbsSignature;

import java.util.List;

/**
 * BBS selective-disclosure helpers for E7.2 (ADR app-layer/006). Wraps ZeroJ's
 * {@link BbsService} for the three roles:
 * <ul>
 *   <li><b>issuer</b> — {@link #issuerKeyPair}, {@link #sign} an attribute set
 *       into a {@link CredentialBody};</li>
 *   <li><b>holder</b> — {@link #disclose} selected attributes as a presentation;</li>
 *   <li><b>verifier</b> — {@link #verifyDisclosure} against the issuer key.</li>
 * </ul>
 * The node only <em>verifies</em> the issuer signature (see
 * {@link CredentialRegistryStateMachine}); issuing and disclosure happen off the
 * node, where the attributes live. Pure Java, no trusted setup.
 * <p>
 * These live in the plugin today; a slim client-only {@code yano-appchain-client-zk}
 * split is a follow-up (they need only zeroj-bbs, not the node).
 */
public final class BbsCredentials {

    private static final BbsCiphersuite SUITE = BbsCiphersuite.BLS12381_SHA256;
    private static final BbsService BBS = BbsService.pureJava(SUITE);

    private BbsCredentials() {
    }

    /** Deterministic issuer key pair from seed material (>= 32 bytes). */
    public static BbsKeyPair issuerKeyPair(byte[] keyMaterial) {
        return BBS.keyPair(keyMaterial, new byte[0]);
    }

    public static BbsPublicKey publicKey(byte[] publicKeyBytes) {
        return new BbsPublicKey(publicKeyBytes, SUITE);
    }

    /** Issuer signs an attribute set into an on-chain credential body. */
    public static CredentialBody sign(BbsKeyPair issuer, String issuerId, String credentialId,
                                      List<byte[]> attributes, byte[] header) {
        BbsSignature signature = BBS.sign(issuer.secretKey(), issuer.publicKey(), attributes, header);
        return new CredentialBody(issuerId, credentialId, signature.bytes(),
                header != null ? header : new byte[0], attributes);
    }

    /** True if the credential's issuer signature verifies (node admission check). */
    public static boolean verifyCredential(BbsPublicKey issuerKey, CredentialBody credential) {
        try {
            BbsSignature signature = new BbsSignature(credential.signature(), SUITE);
            return BBS.verify(issuerKey, signature, credential.attributes(), credential.header());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Holder derives a selective-disclosure presentation revealing only
     * {@code disclosedIndexes}. Returns the CBOR-encoded presentation.
     */
    public static byte[] disclose(BbsPublicKey issuerKey, CredentialBody credential,
                                  int[] disclosedIndexes, byte[] presentationHeader) {
        BbsSignature signature = new BbsSignature(credential.signature(), SUITE);
        BbsPresentation presentation = BBS.derivePresentation(issuerKey, signature,
                credential.attributes(), credential.header(),
                presentationHeader != null ? presentationHeader : new byte[0], disclosedIndexes);
        return BbsPresentationCodec.encode(presentation);
    }

    /** Verifier checks a disclosure against the issuer key. */
    public static boolean verifyDisclosure(BbsPublicKey issuerKey, byte[] presentationCbor) {
        try {
            BbsPresentation presentation = BbsPresentationCodec.decode(presentationCbor);
            return BBS.verifyPresentation(issuerKey, presentation);
        } catch (Exception e) {
            return false;
        }
    }
}
