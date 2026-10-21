package org.yanoproject.appchain.conformance;

import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.AppQueryContext;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.codec.MessageCodec;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.EventDescriptor;
import org.yanoproject.api.appchain.transition.OrderedLogKernel;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.api.appchain.transition.RuleValueView;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionKernel;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.util.concurrent.atomic.AtomicBoolean;

/** Harmless state-machine fixture used only by packaged plugin conformance checks. */
public final class ConformanceStateMachineProvider implements AppStateMachineProvider {
    public static final String ID = "conformance-machine";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AppStateMachine create() {
        AppStateMachine machine = new AppStateMachine() {
            private final AtomicBoolean firstCallback = new AtomicBoolean(true);

            @Override
            public String id() {
                ConformanceTcclProbe.requireCatalogFacade("state-machine identity");
                ConformanceTcclProbe.productCallback(firstCallback,
                        "state-machine identity");
                return ID;
            }

            /** A third-party kernel that declares one verified rule fact (plugin API level 12). */
            @Override
            public Optional<TransitionKernel<?, ?>> transitionKernel() {
                ConformanceTcclProbe.requireCatalogFacade("state-machine transition kernel");
                return Optional.of(new ConformanceKernel());
            }

            @Override
            public void init(AppStateReader state,
                             org.yanoproject.api.appchain.AppChainInfo info) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "state-machine initialization");
            }

            @Override
            public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                              AppEffectEmitter effects) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "state-machine apply");
                // The isolated conformance chain never proposes a block.
            }

            @Override
            public byte[] query(String path, byte[] params, AppQueryContext state) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "state-machine committed query");
                if (!"echo".equals(path)) {
                    throw new UnsupportedOperationException("unsupported conformance query");
                }
                String payload = "echo:" + state.committedHeight() + ":"
                        + HexFormat.of().formatHex(params) + ":"
                        + HexFormat.of().formatHex(state.stateRoot());
                return payload.getBytes(StandardCharsets.UTF_8);
            }
        };
        ConformanceTcclProbe.poisonProviderCallback();
        return machine;
    }

    /**
     * Ordered-log kernel that establishes {@code nonEmpty}: whether the approved record has content.
     * It is a fact only because the kernel computed it from the command it approved. It also declares a
     * single-namespace value view (a stored record's {@code length}) and a one-element write view whose
     * coverage repeats {@code nonEmpty}, so conformance exercises every ADR-031.4 forwarding path.
     */
    static final class ConformanceKernel implements TransitionKernel<byte[], Boolean> {
        static final RuleFact NON_EMPTY = new RuleFact("nonEmpty", RuleFact.Type.BOOLEAN);
        static final RuleFact LENGTH = new RuleFact("length", RuleFact.Type.INTEGER);
        private final OrderedLogKernel delegate = new OrderedLogKernel();

        @Override public MessageCodec<byte[]> codec() { return delegate.codec(); }
        @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state) {
            return command.length > 0;
        }
        @Override public TransitionDecision decide(byte[] command, TransitionContext context, Boolean facts) {
            return delegate.decide(command, context, true);
        }
        @Override public List<RuleFact> ruleFacts() {
            ConformanceTcclProbe.requireCatalogFacade("kernel rule facts");
            return List.of(NON_EMPTY);
        }
        @Override public Map<String, Object> ruleFactValues(byte[] command, TransitionContext context,
                                                            Boolean facts) {
            ConformanceTcclProbe.requireCatalogFacade("kernel rule fact values");
            return Map.of(NON_EMPTY.name(), facts);
        }
        @Override public List<RuleValueView> ruleValueViews() {
            ConformanceTcclProbe.requireCatalogFacade("kernel rule value views");
            return List.of(new RuleValueView("", List.of(LENGTH), List.of()));
        }
        @Override public byte[] ruleValueKey(String namespace, byte[] key) {
            ConformanceTcclProbe.requireCatalogFacade("kernel rule value key");
            return TransitionKernel.super.ruleValueKey(namespace, key);
        }
        @Override public Map<String, Object> ruleValueFields(String namespace, byte[] key, byte[] stored) {
            ConformanceTcclProbe.requireCatalogFacade("kernel rule value fields");
            return Map.of(LENGTH.name(), (long) stored.length);
        }
        @Override public List<RuleFact> ruleWriteFields() {
            ConformanceTcclProbe.requireCatalogFacade("kernel rule write fields");
            return List.of(LENGTH);
        }
        @Override public List<RuleFact> ruleWriteCoverageFields() {
            ConformanceTcclProbe.requireCatalogFacade("kernel rule write coverage fields");
            return List.of(NON_EMPTY);
        }
        @Override public List<Map<String, Object>> ruleWrites(byte[] command) {
            ConformanceTcclProbe.requireCatalogFacade("kernel rule writes");
            return List.of(Map.of(LENGTH.name(), (long) command.length));
        }
        @Override public List<Map<String, Object>> ruleWriteCoverage(byte[] command, TransitionContext context,
                                                                    Boolean facts) {
            ConformanceTcclProbe.requireCatalogFacade("kernel rule write coverage");
            return List.of(Map.of(NON_EMPTY.name(), facts));
        }
        @Override public List<CommandDescriptor> commands() { return delegate.commands(); }
        @Override public List<EventDescriptor> events() { return delegate.events(); }
        @Override public ConfigurationDescriptor configuration() { return delegate.configuration(); }
    }
}
