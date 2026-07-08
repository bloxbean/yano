package com.bloxbean.cardano.yano.appchain.zk;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.OrderedLog;

/**
 * E7.1 ZK admission/consensus gate (ADR app-layer/006). Each message body is an
 * in-body {@link ZkProofBody}; the proof is verified in {@link #apply} on
 * <em>every</em> member (deterministic — identical proof + VK yields the same
 * result), so verification is enforced by consensus, not merely at the
 * proposer's mempool. {@link #validate} is a proposer-side fast-fail only.
 * Verified messages are recorded ordered-log style, so each is provable against
 * the anchored root.
 * <p>
 * The private predicate ("amount ≤ limit", "age ≥ 18", "KYC holds") lives
 * entirely in the proof — the chain and the other members never see the
 * underlying data.
 * <p>
 * EXPERIMENTAL — depends on ZeroJ.
 */
public final class ZkGateStateMachine implements AppStateMachine {

    public static final String ID = "zk-gate";

    private final ZkVerificationService verifier;

    ZkGateStateMachine(ZkVerificationService verifier) {
        this.verifier = verifier;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        // Proposer-side fast-fail only; the authoritative check is in apply(),
        // which every member runs (followers never run validate()).
        ZkProofBody body;
        try {
            body = ZkProofBody.decode(message.getBody());
        } catch (Exception e) {
            return AdmissionResult.reject("not a zk proof body: " + e.getMessage());
        }
        String rejection = verifier.verify(body);
        if (rejection != null) {
            return AdmissionResult.reject("zk verification failed: " + rejection);
        }
        return AdmissionResult.accept();
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer) {
        int index = 0;
        for (AppMessage message : block.messages()) {
            int position = index++;
            // MANDATORY consensus verification — every member re-verifies the
            // proof; a message whose proof does not verify is recorded by no one.
            ZkProofBody body;
            try {
                body = ZkProofBody.decode(message.getBody());
            } catch (Exception e) {
                continue;
            }
            if (verifier.verify(body) != null) {
                continue; // failed verification — not recorded, by all members
            }
            OrderedLog.recordMessage(writer, block.height(), position, message);
        }
        OrderedLog.recordTip(writer, block.height());
    }
}
