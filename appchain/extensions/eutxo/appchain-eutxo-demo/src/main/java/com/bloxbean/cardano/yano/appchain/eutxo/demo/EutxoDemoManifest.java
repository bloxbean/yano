package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import java.util.List;
import java.util.Map;

/** Non-secret, restart-stable workspace identity. */
public record EutxoDemoManifest(
        int schemaVersion,
        String toolVersion,
        String scenario,
        String scenarioVersion,
        String providerArtifact,
        String maturity,
        String network,
        String projectName,
        String chainId,
        int members,
        int httpPortBase,
        int serverPortBase,
        List<String> memberPublicKeys,
        Map<String, String> publicIdentities,
        Map<String, String> secretReferences,
        String createdAt
) {
    public EutxoDemoManifest {
        memberPublicKeys = memberPublicKeys == null ? List.of() : List.copyOf(memberPublicKeys);
        publicIdentities = publicIdentities == null ? Map.of() : Map.copyOf(publicIdentities);
        secretReferences = secretReferences == null ? Map.of() : Map.copyOf(secretReferences);
    }
}
