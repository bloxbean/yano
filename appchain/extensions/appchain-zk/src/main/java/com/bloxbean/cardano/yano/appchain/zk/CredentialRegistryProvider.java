package com.bloxbean.cardano.yano.appchain.zk;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.zeroj.bbs.BbsPublicKey;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ServiceLoader provider for the E7.2 credential registry. Selected by
 * {@code yano.app-chain.state-machine=credential-registry}; issuers configured
 * (decoupled from membership) via:
 * <pre>
 *   yano.app-chain.zk.bbs.issuers[0].id         = hr-dept
 *   yano.app-chain.zk.bbs.issuers[0].public-key = &lt;BBS G2 public-key hex&gt;
 * </pre>
 */
public final class CredentialRegistryProvider implements AppStateMachineProvider {

    private static final Logger log = LoggerFactory.getLogger(CredentialRegistryProvider.class);

    @Override
    public String id() {
        return CredentialRegistryStateMachine.ID;
    }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("The '" + CredentialRegistryStateMachine.ID
                + "' state machine requires configured issuers (yano.app-chain.zk.bbs.issuers[...])");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        Map<String, BbsPublicKey> issuers = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            String base = "zk.bbs.issuers[" + i + "].";
            String id = context.settings().get(base + "id");
            if (id == null || id.isBlank()) {
                continue; // tolerate index gaps
            }
            String pkHex = context.settings().get(base + "public-key");
            if (pkHex == null || pkHex.isBlank()) {
                throw new IllegalStateException("Missing public-key for BBS issuer '" + id + "'");
            }
            issuers.put(id, BbsCredentials.publicKey(HexUtil.decodeHexString(pkHex.trim())));
        }
        if (issuers.isEmpty()) {
            throw new IllegalStateException("state-machine=credential-registry but no issuers configured "
                    + "(yano.app-chain.zk.bbs.issuers[0].id ...) for chain '" + context.chainId() + "'");
        }
        log.info("Credential registry enabled for chain '{}': issuers={}", context.chainId(), issuers.keySet());
        return new CredentialRegistryStateMachine(issuers);
    }
}
