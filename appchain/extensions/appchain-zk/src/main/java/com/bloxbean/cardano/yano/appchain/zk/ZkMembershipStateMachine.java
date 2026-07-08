package com.bloxbean.cardano.yano.appchain.zk;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;

import java.nio.charset.StandardCharsets;

/**
 * E7.3 anonymous membership submissions (ADR app-layer/006). The author proves
 * set membership (via the E7.1 verification path) instead of signing with an
 * identifiable key; a one-time <b>nullifier</b> replaces per-sender sequence
 * numbers. Per the resolved design (OQ#3):
 * <ul>
 *   <li>Anonymous submissions have no per-sender sequence.</li>
 *   <li>Dedup is per-nullifier and applied deterministically in {@link #apply}
 *       against the MPF state (rollback-safe, agreed by all members) — an
 *       admission-time check would not be authoritative.</li>
 *   <li>The nullifier {@code context} is an application policy knob: bind it to
 *       {@code hash(chainId, topic, epoch)} for one-action-per-member-per-epoch
 *       (voting, sealed bids), or a per-message tag for replay-only protection.</li>
 * </ul>
 * A repeated nullifier is a deterministic no-op (double-action prevented). The
 * anonymous payload is recorded keyed by the nullifier, provable without
 * revealing the author.
 * <p>
 * Scope note: the logical author is anonymous within the member set; the
 * transport envelope still carries the relaying node's signature. Promoting to
 * a fully anonymous transport {@code authScheme=2} touches the core auth path
 * and is a follow-up.
 * <p>
 * EXPERIMENTAL — depends on ZeroJ.
 */
public final class ZkMembershipStateMachine implements AppStateMachine {

    public static final String ID = "zk-membership";
    private static final byte[] NULLIFIER_PREFIX = "~null/".getBytes(StandardCharsets.UTF_8);

    private final ZkVerificationService verifier;

    ZkMembershipStateMachine(ZkVerificationService verifier) {
        this.verifier = verifier;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        // Proposer-side fast-fail only; the authoritative checks are in apply().
        MembershipProofBody body;
        try {
            body = MembershipProofBody.decode(message.getBody());
        } catch (Exception e) {
            return AdmissionResult.reject("not a membership proof body: " + e.getMessage());
        }
        if (!nullifierBoundToProof(body)) {
            return AdmissionResult.reject("nullifier is not bound to the proof (not a public input)");
        }
        String rejection = verifier.verify(body.proofBody());
        if (rejection != null) {
            return AdmissionResult.reject("membership proof failed: " + rejection);
        }
        return AdmissionResult.accept();
    }

    /**
     * The nullifier MUST be one of the proof's public inputs — otherwise it is
     * an attacker-controlled field unbound to the proof, and replaying a valid
     * proof with a fresh nullifier would forge a second anonymous action.
     */
    private static boolean nullifierBoundToProof(MembershipProofBody body) {
        java.math.BigInteger nullifier = new java.math.BigInteger(1, body.nullifier());
        return body.publicInputs().contains(nullifier);
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer) {
        for (AppMessage message : block.messages()) {
            MembershipProofBody body;
            try {
                body = MembershipProofBody.decode(message.getBody());
            } catch (Exception e) {
                continue;
            }
            // MANDATORY consensus checks (every member; followers skip validate):
            // the nullifier must be bound to the proof, and the proof must verify.
            if (!nullifierBoundToProof(body) || verifier.verify(body.proofBody()) != null) {
                continue;
            }
            byte[] nullifierKey = concat(NULLIFIER_PREFIX, body.nullifier());
            if (writer.get(nullifierKey).isPresent()) {
                continue; // nullifier already consumed — double action, no-op for all members
            }
            // Consume the nullifier and record the anonymous payload, both
            // committed to the MPF state (rollback-safe).
            writer.put(nullifierKey, CborSerializationUtil.serialize(new UnsignedInteger(block.height())));
            byte[] recordKey = concat("~anon/".getBytes(StandardCharsets.UTF_8), body.nullifier());
            Array entry = new Array();
            entry.add(new UnsignedInteger(block.height()));
            entry.add(new ByteString(body.context() != null ? body.context() : new byte[0]));
            entry.add(new ByteString(body.payload() != null ? body.payload() : new byte[0]));
            writer.put(recordKey, CborSerializationUtil.serialize(entry));
        }
    }

    public static byte[] nullifierKey(byte[] nullifier) {
        return concat(NULLIFIER_PREFIX, nullifier);
    }

    public static byte[] recordKey(byte[] nullifier) {
        return concat("~anon/".getBytes(StandardCharsets.UTF_8), nullifier);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
