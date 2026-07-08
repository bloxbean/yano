package com.bloxbean.cardano.yano.appchain.zk;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.zeroj.bbs.BbsPublicKey;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * E7.2 anchored credential registry (ADR app-layer/006). Admits a credential
 * only if its issuer BBS signature verifies against a configured issuer key
 * (issuers are decoupled from chain membership, OQ#5), then records
 * {@code credentialId → [issuerId, recordHash]} — provable/anchorable, so a
 * third party can later confirm a disclosed attribute came from the anchored,
 * validly-issued original. No consensus change beyond the admission check.
 * <p>
 * EXPERIMENTAL — depends on ZeroJ BBS.
 */
public final class CredentialRegistryStateMachine implements AppStateMachine {

    public static final String ID = "credential-registry";

    private final Map<String, BbsPublicKey> issuers;

    CredentialRegistryStateMachine(Map<String, BbsPublicKey> issuers) {
        this.issuers = Map.copyOf(issuers);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        CredentialBody credential;
        try {
            credential = CredentialBody.decode(message.getBody());
        } catch (Exception e) {
            return AdmissionResult.reject("not a credential body: " + e.getMessage());
        }
        BbsPublicKey issuerKey = issuers.get(credential.issuerId());
        if (issuerKey == null) {
            return AdmissionResult.reject("unknown credential issuer '" + credential.issuerId()
                    + "' (not in zk.bbs.issuers config)");
        }
        if (!BbsCredentials.verifyCredential(issuerKey, credential)) {
            return AdmissionResult.reject("invalid issuer BBS signature for credential '"
                    + credential.credentialId() + "'");
        }
        return AdmissionResult.accept();
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer) {
        for (AppMessage message : block.messages()) {
            CredentialBody credential;
            try {
                credential = CredentialBody.decode(message.getBody());
            } catch (Exception e) {
                continue;
            }
            // MANDATORY consensus check — every member re-verifies the issuer's
            // BBS signature here (followers never run validate()). A credential
            // from an unknown issuer or with a bad signature is recorded by no
            // one, so a forged/unissued credential can never be anchored.
            BbsPublicKey issuerKey = issuers.get(credential.issuerId());
            if (issuerKey == null || !BbsCredentials.verifyCredential(issuerKey, credential)) {
                continue;
            }
            byte[] key = ("cred/" + credential.credentialId()).getBytes(StandardCharsets.UTF_8);
            Array entry = new Array();
            entry.add(new UnicodeString(credential.issuerId()));
            entry.add(new ByteString(Blake2bUtil.blake2bHash256(message.getBody())));
            writer.put(key, CborSerializationUtil.serialize(entry));
        }
    }

    public static byte[] credentialKey(String credentialId) {
        return ("cred/" + credentialId).getBytes(StandardCharsets.UTF_8);
    }
}
