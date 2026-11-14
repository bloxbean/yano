package org.yanoproject.runtime.host;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yanoproject.p2p.peer.PeerClientFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class DefaultN2NExtensionRegistryTest {
    @Test
    void createsFreshAgentsOnlyAfterSealingAndRejectsDuplicateOrLateRegistration() {
        DefaultN2NExtensionRegistry registry = new DefaultN2NExtensionRegistry();
        AtomicInteger created = new AtomicInteger();
        registry.registerInbound(100, () -> { created.incrementAndGet(); return agent(100); });
        assertThat(created).hasValue(0);
        assertThatThrownBy(registry::inboundFactories).hasMessageContaining("Seal N2N");
        assertThatThrownBy(() -> registry.registerInbound(100, () -> agent(100))).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> registry.registerInbound(2, () -> agent(2))).isInstanceOf(IllegalArgumentException.class);
        registry.registerInbound(103, () -> agent(103));
        registry.seal();
        registry.seal();
        var factories = registry.inboundFactories();
        assertThat(factories).hasSize(2);
        assertThat(factories.getFirst().createAgent()).isNotSameAs(factories.getFirst().createAgent());
        assertThat(created).hasValue(2);
        assertThat(factories.get(1).createAgent().getProtocolId()).isEqualTo(103);
        assertThatThrownBy(() -> registry.registerInbound(104, () -> agent(104))).hasMessageContaining("initialization");
        assertThatThrownBy(() -> registry.decorateOutbound(factory -> factory)).hasMessageContaining("initialization");
    }

    @Test
    void rejectsWrongProtocolNullAndFailingFactoriesAtSessionConstruction() {
        DefaultN2NExtensionRegistry registry = new DefaultN2NExtensionRegistry();
        registry.registerInbound(100, () -> agent(103));
        registry.registerInbound(101, () -> null);
        registry.registerInbound(102, () -> { throw new IllegalStateException("failed factory"); });
        registry.seal();
        assertThatThrownBy(() -> registry.inboundFactories().get(0).createAgent()).hasMessageContaining("registered ID 100");
        assertThatThrownBy(() -> registry.inboundFactories().get(1).createAgent()).hasMessageContaining("returned null");
        assertThatThrownBy(() -> registry.inboundFactories().get(2).createAgent()).hasMessageContaining("failed factory");
    }

    @Test
    void composesExistingOutboundFactoryAndReportsAbsentSharedSessions() {
        DefaultN2NExtensionRegistry registry = new DefaultN2NExtensionRegistry();
        List<String> calls = new ArrayList<>();
        PeerClientFactory original = (endpoint, point) -> { calls.add("original"); return null; };
        registry.decorateOutbound(next -> (endpoint, point) -> { calls.add("first"); return next.create(endpoint, point); });
        registry.decorateOutbound(next -> (endpoint, point) -> { calls.add("second"); return next.create(endpoint, point); });
        registry.seal();
        registry.outboundFactory(original).create(null, null);
        assertThat(calls).containsExactly("second", "first", "original");
        DefaultN2NExtensionRegistry standalone = new DefaultN2NExtensionRegistry(false);
        assertThat(standalone.hasSharedOutboundSessions()).isFalse();
        assertThatThrownBy(() -> standalone.decorateOutbound(factory -> factory))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("unavailable");
    }

    private static Agent<?> agent(int id) {
        return switch (id) {
            case 100 -> new AppMsgSubmissionServerAgent(AppMsgSubmissionConfig.builder()
                    .chainIds(Set.of("host-api-test")).build());
            case 103 -> new AppChainSyncServerAgent((chain, from, to) ->
                    new AppChainSyncServerAgent.BlockRangeProvider.Range(List.of(), 0));
            default -> throw new IllegalArgumentException("Unsupported test agent " + id);
        };
    }
}
