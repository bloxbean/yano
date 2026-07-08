package com.bloxbean.cardano.yano.appchain.zk;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * E7.1 ZK admission/consensus gate (ADR app-layer/006). Each message body is an
 * in-body {@link ZkProofBody}; the proof is verified at admission
 * ({@link #validate}) and — when {@code zk.verify-in-apply=true}, the default —
 * re-verified deterministically in {@link #apply} for consensus-critical
 * enforcement (every member re-executes and agrees). Verified messages are
 * recorded ordered-log style, so each is provable against the anchored root.
 * <p>
 * The private predicate ("amount ≤ limit", "age ≥ 18", "KYC holds") lives
 * entirely in the proof — the chain and the other members never see the
 * underlying data.
 * <p>
 * EXPERIMENTAL — depends on ZeroJ.
 */
public final class ZkGateStateMachine implements AppStateMachine {

    public static final String ID = "zk-gate";
    private static final Logger log = LoggerFactory.getLogger(ZkGateStateMachine.class);

    private final ZkVerificationService verifier;
    private final boolean verifyInApply;

    ZkGateStateMachine(ZkVerificationService verifier, boolean verifyInApply) {
        this.verifier = verifier;
        this.verifyInApply = verifyInApply;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
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
            if (verifyInApply) {
                // Deterministic re-verification: identical proof + VK yields the
                // same result on every member, so a rejected message is skipped
                // consistently (consensus-critical enforcement).
                ZkProofBody body;
                try {
                    body = ZkProofBody.decode(message.getBody());
                } catch (Exception e) {
                    continue;
                }
                if (verifier.verify(body) != null) {
                    continue; // failed re-verification — not recorded, by all members
                }
            }
            Array entry = new Array();
            entry.add(new UnsignedInteger(block.height()));
            entry.add(new UnsignedInteger(position));
            entry.add(new UnicodeString(message.getTopic() != null ? message.getTopic() : ""));
            entry.add(new ByteString(message.getSender()));
            writer.put(message.getMessageId(), CborSerializationUtil.serialize(entry));
        }
        writer.put("~tip".getBytes(StandardCharsets.UTF_8),
                CborSerializationUtil.serialize(new UnsignedInteger(block.height())));
    }
}
