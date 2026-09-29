package org.yanoproject.ledger.rules.shadow;

import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.view.RecordingLedgerView;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A self-contained replay bundle for one engine disagreement (ADR-056 §7, {@code shadow-dump-dir}): the
 * transaction bytes, the request's rule, origin and environment, both verdicts, and every ledger-view read
 * either engine performed, with its outcome.
 *
 * <p>{@link #replayView()} answers exactly those reads, so a test can run any engine against the state the
 * disagreement was observed on. The file is JSON ({@code <txhash>-<shadow engine>.json}).</p>
 *
 * @param txHash          the transaction id
 * @param txCbor          the transaction bytes as received
 * @param rule            the request's rule
 * @param origin          the request's origin
 * @param env             the request's environment
 * @param admission       the admission engine and its outcome
 * @param shadow          the shadow engine and its outcome
 * @param reads           the recorded reads, deduplicated by (method, key), first read wins
 */
public record ShadowDumpBundle(String txHash, byte[] txCbor, TxValidationRequest.Rule rule,
                               TxValidationRequest.Origin origin, ValidationEnv env, RecordedOutcome admission,
                               RecordedOutcome shadow, List<RecordingLedgerView.Read> reads) {

    /**
     * An engine's recorded outcome: the verdict and every failure (effects are not recorded).
     *
     * @param engine   the engine name
     * @param valid    whether the outcome was valid
     * @param failures the failures of an invalid outcome, in order
     */
    public record RecordedOutcome(String engine, boolean valid, List<LedgerFailure> failures) {
        public RecordedOutcome {
            Objects.requireNonNull(engine, "engine");
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
            if (valid != failures.isEmpty()) {
                throw new IllegalArgumentException("a valid outcome has no failures, an invalid one has some");
            }
        }

        public static RecordedOutcome of(String engine, TxValidationOutcome outcome) {
            return switch (outcome) {
                case TxValidationOutcome.Valid v -> new RecordedOutcome(engine, true, List.of());
                case TxValidationOutcome.Invalid i -> new RecordedOutcome(engine, false, i.failures());
            };
        }

        public Verdict verdict() {
            return new Verdict(valid, valid ? null : failures.getFirst());
        }
    }

    public ShadowDumpBundle {
        Objects.requireNonNull(txHash, "txHash");
        txCbor = Objects.requireNonNull(txCbor, "txCbor").clone();
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(shadow, "shadow");
        reads = dedupe(Objects.requireNonNull(reads, "reads"));
    }

    @Override
    public byte[] txCbor() {
        return txCbor.clone();
    }

    private static List<RecordingLedgerView.Read> dedupe(List<RecordingLedgerView.Read> reads) {
        Map<String, RecordingLedgerView.Read> byKey = new LinkedHashMap<>();
        for (RecordingLedgerView.Read read : reads) {
            byKey.putIfAbsent(read.method() + "\u0000" + read.key(), read);
        }
        return List.copyOf(byKey.values());
    }

    /** @return a view that answers the recorded reads and is unavailable for anything else */
    public ReplayLedgerView replayView() {
        return new ReplayLedgerView(reads);
    }

    /** @return the request the bundle was recorded for, against {@link #replayView()} */
    public TxValidationRequest replayRequest() {
        return new TxValidationRequest(txCbor, replayView(), env, rule, origin, null);
    }

    // ------------------------------------------------------------------ files

    /**
     * Writes the bundle into {@code directory} (created when missing), atomically.
     *
     * @return the written file
     */
    public Path write(Path directory) {
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(txHash + "-" + shadow.engine() + ".json");
            Path temp = Files.createTempFile(directory, "." + txHash, ".tmp");
            try {
                ViewReadCodec.MAPPER.writer(SerializationFeature.INDENT_OUTPUT).writeValue(temp.toFile(), toJson());
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write shadow dump for " + txHash, e);
        }
    }

    /** Reads a bundle written by {@link #write(Path)}. */
    public static ShadowDumpBundle read(Path file) {
        try {
            return fromJson(ViewReadCodec.MAPPER.readTree(file.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read shadow dump " + file, e);
        }
    }

    ObjectNode toJson() {
        ObjectNode root = ViewReadCodec.MAPPER.createObjectNode();
        root.put("version", 1);
        root.put("txHash", txHash);
        root.put("txCbor", HexUtil.encodeHexString(txCbor));
        root.put("rule", rule.name());
        root.put("origin", origin.name());
        ObjectNode e = root.putObject("env");
        e.put("slot", env.currentSlot());
        e.put("epoch", env.currentEpoch());
        e.put("protocolMajor", env.protocolMajor());
        e.put("protocolMinor", env.protocolMinor());
        e.put("networkId", env.networkId().name());
        e.put("slotZeroTime", env.slotConfig().getZeroTime());
        e.put("slotZero", env.slotConfig().getZeroSlot());
        e.put("slotLength", env.slotConfig().getSlotLength());
        e.put("phase2EnvDigest", HexUtil.encodeHexString(env.phase2EnvDigest()));
        e.put("forecastBasisSlot", env.forecastBasisSlot());
        root.set("admission", outcome(admission));
        root.set("shadow", outcome(shadow));
        ArrayNode readNodes = root.putArray("reads");
        reads.forEach(r -> readNodes.add(ViewReadCodec.encode(r)));
        return root;
    }

    private static ObjectNode outcome(RecordedOutcome outcome) {
        ObjectNode node = ViewReadCodec.MAPPER.createObjectNode();
        node.put("engine", outcome.engine());
        node.put("valid", outcome.valid());
        if (!outcome.valid()) {
            ArrayNode failures = node.putArray("failures");
            for (LedgerFailure f : outcome.failures()) {
                ObjectNode failure = failures.addObject();
                failure.put("rule", f.rule().name());
                failure.put("constructor", f.constructor());
                failure.put("phase", f.phase().name());
                failure.put("detail", f.detail());
            }
        }
        return node;
    }

    static ShadowDumpBundle fromJson(JsonNode root) {
        JsonNode e = root.get("env");
        ValidationEnv env = new ValidationEnv(e.get("slot").asLong(), e.get("epoch").asLong(),
                e.get("protocolMajor").asInt(), e.get("protocolMinor").asInt(),
                NetworkId.valueOf(e.get("networkId").asText()),
                new SlotConfig(e.get("slotLength").asInt(), e.get("slotZero").asLong(), e.get("slotZeroTime").asLong()),
                HexUtil.decodeHexString(e.get("phase2EnvDigest").asText()),
                // Written since ADR-056 Phase 7a; older bundles based the horizon on the slot.
                e.path("forecastBasisSlot").asLong(e.get("slot").asLong()));
        List<RecordingLedgerView.Read> reads = new ArrayList<>();
        root.get("reads").forEach(r -> reads.add(ViewReadCodec.decode(r)));
        return new ShadowDumpBundle(root.get("txHash").asText(), HexUtil.decodeHexString(root.get("txCbor").asText()),
                TxValidationRequest.Rule.valueOf(root.get("rule").asText()),
                TxValidationRequest.Origin.valueOf(root.get("origin").asText()), env,
                outcomeOf(root.get("admission")), outcomeOf(root.get("shadow")), reads);
    }

    private static RecordedOutcome outcomeOf(JsonNode node) {
        List<LedgerFailure> failures = new ArrayList<>();
        node.path("failures").forEach(f -> failures.add(new LedgerFailure(
                LedgerRuleName.valueOf(f.get("rule").asText()), f.get("constructor").asText(),
                LedgerFailure.Phase.valueOf(f.get("phase").asText()), f.path("detail").asText(""))));
        return new RecordedOutcome(node.get("engine").asText(), node.get("valid").asBoolean(), failures);
    }
}
