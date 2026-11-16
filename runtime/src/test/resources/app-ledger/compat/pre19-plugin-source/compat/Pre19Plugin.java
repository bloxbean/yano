package compat;

import org.yanoproject.api.plugin.NodePlugin;
import org.yanoproject.api.plugin.PluginContext;
import org.yanoproject.api.plugin.PluginDigestMode;

import java.util.List;

/** Compiled once against pre19; tests load the committed bytes without recompilation. */
public final class Pre19Plugin implements NodePlugin {
    private PluginContext context;
    private List<String> calls;

    public String id() { return "compat.pre19"; }
    public String version() { return "1"; }

    @SuppressWarnings("unchecked")
    public void init(PluginContext context) {
        this.context = context;
        calls = context.getService("compat.calls", List.class).orElseThrow();
        if (!"legacy".equals(context.config().get("compat.mode"))) {
            throw new IllegalStateException("Legacy configuration missing");
        }
        calls.add("init:" + PluginDigestMode.ARTIFACT_CLOSURE.name());
        context.registerService("compat.stable", this);
    }

    public void start() {
        if (context.getService("compat.stable", NodePlugin.class).orElseThrow() != this) {
            throw new IllegalStateException("Service identity changed");
        }
        context.registerService("compat.running", this);
        calls.add("start");
    }
    public void stop() { calls.add("stop"); }
    public void close() { calls.add("close"); }
}
