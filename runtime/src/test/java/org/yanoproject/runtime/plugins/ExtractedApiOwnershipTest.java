package org.yanoproject.runtime.plugins;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractedApiOwnershipTest {
    @Test
    void compatibilityCoordinatesDoNotDuplicateMovedDefinitionsOrWireResources() throws Exception {
        var loader = getClass().getClassLoader();
        for (String resource : List.of(
                "org/yanoproject/api/plugin/NodePlugin.class",
                "org/yanoproject/api/appchain/anchor/AnchorDatumV1.class",
                "org/yanoproject/api/appchain/effects/AppEffectExecutorFactory.class",
                "org/yanoproject/api/appchain/l1view/ProtocolParamsCanonicalCodec.class",
                "org/yanoproject/api/utxo/UtxoState.class",
                "org/yanoproject/runtime/kernel/Subsystem.class",
                "org/yanoproject/runtime/chain/BlockBodyRetentionRegistry$Registration.class",
                "cost-model-opnames/PlutusV3.txt",
                "org/yanoproject/api/plugin/PluginDigestMode.class",
                "org/yanoproject/api/host/HostApi.class",
                "org/yanoproject/api/appchain/AppBlock.class",
                "org/yanoproject/api/appchain/l1view/L1Observer.class",
                "org/yanoproject/api/events/AppBlockFinalizedEvent.class",
                "org/yanoproject/appchain/config/AppChainConfigParser.class",
                "org/yanoproject/runtime/appchain/AppChainEngine.class",
                "org/yanoproject/runtime/appchain/AppLedgerRuntime.class",
                "org/yanoproject/runtime/appchain/AppChainSubsystem.class",
                "org/yanoproject/runtime/appchain/AppLedgerManager.class",
                "org/yanoproject/runtime/appchain/AppChainManager.class",
                "META-INF/plutus/AnchorThreadPolicy.plutus.json",
                "META-INF/plutus/AnchorValidator.plutus.json",
                "META-INF/yano/topics/v1/topics.cddl",
                "META-INF/yano/contracts/l1-observation/v1/golden-vectors.properties")) {
            assertThat(Collections.list(loader.getResources(resource)))
                    .as("One owning artifact for %s", resource).hasSize(1);
        }
    }
}
