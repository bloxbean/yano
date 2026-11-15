package org.yanoproject.runtime.plugins;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractedApiOwnershipTest {
    @Test
    void compatibilityCoordinatesDoNotDuplicateMovedDefinitionsOrWireResources() throws Exception {
        var loader = getClass().getClassLoader();
        // The preview namespace migration deliberately requires framework clients
        // to recompile; retaining aliases would hide an accidental duplicate owner.
        for (String legacy : List.of(
                "org/yanoproject/api/appchain/AppBlock.class",
                "org/yanoproject/api/appchain/AppChainConfig.class",
                "org/yanoproject/runtime/appchain/AppChainSubsystem.class",
                "org/yanoproject/runtime/appchain/AppChainManager.class")) {
            assertThat(Collections.list(loader.getResources(legacy)))
                    .as("Retired framework definition %s", legacy).isEmpty();
        }
        for (String resource : List.of(
                "org/yanoproject/api/plugin/NodePlugin.class",
                "org/yanoproject/x/framework/cardano/api/anchor/AnchorDatumV1.class",
                "org/yanoproject/x/framework/cardano/api/effects/AppEffectExecutorFactory.class",
                "org/yanoproject/x/framework/cardano/api/l1view/ProtocolParamsCanonicalCodec.class",
                "org/yanoproject/api/utxo/UtxoState.class",
                "org/yanoproject/runtime/kernel/Subsystem.class",
                "org/yanoproject/runtime/chain/BlockBodyRetentionRegistry$Registration.class",
                "cost-model-opnames/PlutusV3.txt",
                "org/yanoproject/api/plugin/PluginDigestMode.class",
                "org/yanoproject/api/host/HostApi.class",
                "org/yanoproject/x/framework/api/appchain/AppBlock.class",
                "org/yanoproject/x/framework/api/appchain/l1view/L1Observer.class",
                "org/yanoproject/x/framework/api/events/AppBlockFinalizedEvent.class",
                "org/yanoproject/x/framework/config/AppChainConfigParser.class",
                "org/yanoproject/x/framework/runtime/appchain/AppChainEngine.class",
                "org/yanoproject/x/framework/runtime/appchain/AppLedgerRuntime.class",
                "org/yanoproject/x/framework/cardano/AppChainSubsystem.class",
                "org/yanoproject/x/framework/runtime/appchain/AppLedgerManager.class",
                "org/yanoproject/x/framework/cardano/AppChainManager.class",
                "META-INF/plutus/AnchorThreadPolicy.plutus.json",
                "META-INF/plutus/AnchorValidator.plutus.json",
                "META-INF/yano/topics/v1/topics.cddl",
                "META-INF/yano/contracts/l1-observation/v1/golden-vectors.properties")) {
            assertThat(Collections.list(loader.getResources(resource)))
                    .as("One owning artifact for %s", resource).hasSize(1);
        }
    }
}
