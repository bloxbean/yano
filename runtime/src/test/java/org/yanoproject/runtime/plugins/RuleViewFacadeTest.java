package org.yanoproject.runtime.plugins;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.codec.MessageCodec;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.EventDescriptor;
import org.yanoproject.api.appchain.transition.OrderedLogKernel;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.api.appchain.transition.RuleValueView;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.catalog.ContributionKind;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-031.4 typed views across the plugin facade: declarations are bounded and type-checked, value keys keep the
 * exception class the engine depends on, and decoded values and write elements are host-owned, bounded snapshots.
 */
class RuleViewFacadeTest {
    private static final RuleFact STATUS = new RuleFact("status", RuleFact.Type.TEXT);

    /** A test kernel whose typed-view methods are supplied per test. */
    private static class ViewKernel implements TransitionKernel<byte[], Boolean> {
        private final OrderedLogKernel delegate = new OrderedLogKernel();
        Function<Integer, List<RuleValueView>> views = ignored -> List.of();
        List<RuleFact> writeFields = List.of();
        List<RuleFact> coverageFields = List.of();
        byte[] kept = {5};
        Map<String, Object> decoded = Map.of();
        List<Map<String, Object>> writes = List.of();

        @Override public MessageCodec<byte[]> codec() { return delegate.codec(); }
        @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state) { return true; }
        @Override public TransitionDecision decide(byte[] command, TransitionContext context, Boolean facts) {
            return delegate.decide(command, context, facts);
        }
        @Override public List<CommandDescriptor> commands() { return delegate.commands(); }
        @Override public List<EventDescriptor> events() { return delegate.events(); }
        @Override public ConfigurationDescriptor configuration() { return delegate.configuration(); }
        @Override public List<RuleValueView> ruleValueViews() { return views.apply(0); }
        @Override public byte[] ruleValueKey(String namespace, byte[] key) {
            if (namespace.equals("bad")) throw new IllegalArgumentException("unknown namespace");
            return namespace.equals("null") ? null : kept;
        }
        @Override public Map<String, Object> ruleValueFields(String namespace, byte[] key, byte[] stored) {
            return decoded;
        }
        @Override public List<RuleFact> ruleWriteFields() { return writeFields; }
        @Override public List<RuleFact> ruleWriteCoverageFields() { return coverageFields; }
        @Override public List<Map<String, Object>> ruleWrites(byte[] command) { return writes; }
    }

    @SuppressWarnings("unchecked")
    private TransitionKernel<byte[], Boolean> facade(ViewKernel raw) {
        AppStateMachine machine = new AppStateMachine() {
            @Override public String id() { return "views"; }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects) { }
            @Override public Optional<TransitionKernel<?, ?>> transitionKernel() { return Optional.of(raw); }
        };
        AppStateMachineProvider provider = new AppStateMachineProvider() {
            @Override public String id() { return "views"; }
            @Override public AppStateMachine create() { return machine; }
        };
        var wrapped = (AppStateMachineProvider) PluginSpiFacades.provider(ContributionKind.APP_STATE_MACHINE,
                provider, getClass().getClassLoader(), "views-bundle", "views", provider.getClass().getName());
        return (TransitionKernel<byte[], Boolean>) wrapped.create().transitionKernel().orElseThrow();
    }

    private static List<RuleFact> facts(int count) {
        return IntStream.range(0, count).mapToObj(index -> new RuleFact("f" + index, RuleFact.Type.INTEGER)).toList();
    }

    @Test
    void theFacadeOverridesEveryKernelMethod() {
        Class<?> facade = facade(new ViewKernel()).getClass();
        List<String> missing = new ArrayList<>();
        for (Method method : TransitionKernel.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            try {
                facade.getDeclaredMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException absent) {
                missing.add(method.getName());
            }
        }
        // A method the facade inherits would run the interface default instead of the plugin's override.
        assertThat(missing).isEmpty();
    }

    @Test
    void declarationsAreBoundedAndTypeChecked() {
        var raw = new ViewKernel();
        raw.views = ignored -> IntStream.range(0, RuleValueView.MAX_VIEWS + 1)
                .mapToObj(index -> new RuleValueView("n" + index, List.of(STATUS), List.of())).toList();
        assertThatThrownBy(() -> facade(raw).ruleValueViews()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too many kernel rule value views");
        raw.views = ignored -> {
            List<Object> foreign = new ArrayList<>();
            foreign.add(new RuleValueView("", List.of(STATUS), List.of()));
            foreign.add("not a view");
            @SuppressWarnings("unchecked")
            List<RuleValueView> raw2 = (List<RuleValueView>) (List<?>) foreign;
            return raw2;
        };
        assertThatThrownBy(() -> facade(raw).ruleValueViews()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RuleValueView");
        raw.writeFields = facts(RuleValueView.MAX_FIELDS + 1);
        assertThatThrownBy(() -> facade(raw).ruleWriteFields()).isInstanceOf(IllegalStateException.class);
        raw.writeFields = new ArrayList<>(List.of(STATUS));
        raw.writeFields.add(null);
        assertThatThrownBy(() -> facade(raw).ruleWriteFields()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RuleFact");
        raw.coverageFields = facts(RuleValueView.MAX_FIELDS + 1);
        assertThatThrownBy(() -> facade(raw).ruleWriteCoverageFields()).isInstanceOf(IllegalStateException.class);
        raw.coverageFields = facts(RuleValueView.MAX_FIELDS);
        assertThat(facade(raw).ruleWriteCoverageFields()).hasSize(RuleValueView.MAX_FIELDS);
    }

    @Test
    void valueKeysKeepTheirExceptionClassAndAreCopies() {
        var raw = new ViewKernel();
        var kernel = facade(raw);
        assertThatThrownBy(() -> kernel.ruleValueKey("bad", new byte[]{1}))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("unknown namespace");
        assertThat(kernel.ruleValueKey("null", new byte[]{1})).isNull();
        byte[] local = kernel.ruleValueKey("", new byte[]{1});
        local[0] = 9;
        assertThat(raw.kept).containsExactly(5);
        assertThat(kernel.ruleValueKey("", new byte[]{1})).containsExactly(5);
    }

    @Test
    void decodedValuesAndWriteElementsBecomeBoundedHostOwnedSnapshots() {
        var raw = new ViewKernel();
        Map<String, Object> decoded = new LinkedHashMap<>();
        decoded.put("big", BigInteger.TWO.pow(70));
        decoded.put("small", 3);
        decoded.put("plugin", new Object());
        decoded.put("fine", 3L);
        raw.decoded = decoded;
        Map<String, Object> snapshot = facade(raw).ruleValueFields("", new byte[]{1}, new byte[]{2});
        // Only host scalar types survive; the rest become one host marker the engine rejects as a violation.
        assertThat(snapshot.get("fine")).isEqualTo(3L);
        for (String name : List.of("big", "small", "plugin")) {
            assertThat(snapshot.get(name)).as(name).isNotInstanceOf(BigInteger.class).isNotInstanceOf(Integer.class)
                    .isNotNull().isNotEqualTo(decoded.get(name));
        }
        Map<String, Object> wide = new LinkedHashMap<>();
        for (int index = 0; index < 100; index++) wide.put("f" + index, (long) index);
        raw.writes = List.of(wide);
        assertThat(facade(raw).ruleWrites(new byte[]{1}).getFirst()).hasSize(2 * RuleValueView.MAX_FIELDS + 1);
    }
}
