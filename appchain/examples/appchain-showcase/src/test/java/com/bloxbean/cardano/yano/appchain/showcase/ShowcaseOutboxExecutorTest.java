package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShowcaseOutboxExecutorTest {
    @TempDir
    Path temporary;

    @Test
    void retryProducesOneByteIdenticalReceipt() throws Exception {
        ShowcaseOutboxExecutor executor = new ShowcaseOutboxExecutor(temporary.resolve("outbox"));
        PendingEffect effect = effect("{\"order\":1}".getBytes());

        EffectExecution first = executor.execute(context(), effect);
        EffectExecution second = executor.execute(context(), effect);

        assertThat(first).isInstanceOf(EffectExecution.Confirmed.class);
        assertThat(((EffectExecution.Confirmed) second).externalRef())
                .containsExactly(((EffectExecution.Confirmed) first).externalRef());
        assertThat(((EffectExecution.Confirmed) second).detailHash())
                .containsExactly(((EffectExecution.Confirmed) first).detailHash());
        assertThat(Files.list(temporary.resolve("outbox"))).hasSize(1);
        String receipt = Files.readString(Files.list(temporary.resolve("outbox")).findFirst().orElseThrow());
        assertThat(receipt).contains(effect.effectId().canonical(), effect.effectId().hashHex(),
                ShowcaseOutboxExecutor.TYPE);
        assertThat(executor.operationalSnapshot().attempts()).isEqualTo(2);
        assertThat(executor.operationalSnapshot().successes()).isEqualTo(2);
    }

    @Test
    void factoryIsOptInStrictAndRequiresAbsoluteDirectory() {
        ShowcaseOutboxExecutorFactory factory = new ShowcaseOutboxExecutorFactory();

        assertThat(factory.create("chain", Map.of("enabled", "false"))).isEmpty();
        assertThatThrownBy(() -> factory.create("chain", Map.of(
                "enabled", "true", "directory", "relative")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
        assertThatThrownBy(() -> factory.create("chain", Map.of("unknown", "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown");
    }

    private static PendingEffect effect(byte[] payload) {
        return PendingEffect.of(new EffectRecord(EffectRecord.RECORD_VERSION,
                "workflow-chain", 9, 1, ShowcaseOutboxExecutor.TYPE, payload,
                "showcase/order-release/release-1", FinalityGate.APP_FINAL,
                ResultPolicy.CHAIN, 109, null));
    }

    private static EffectExecutionContext context() {
        return new EffectExecutionContext() {
            @Override public String chainId() { return "workflow-chain"; }
            @Override public long tipHeight() { return 10; }
            @Override public long anchoredHeight() { return 0; }
            @Override public int attempt() { return 1; }
            @Override public Map<String, String> settings() { return Map.of(); }
        };
    }
}
