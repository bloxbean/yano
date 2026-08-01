package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutorOperationalSnapshot;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Idempotently writes one deterministic JSON receipt per effect identity. */
public final class ShowcaseOutboxExecutor implements AppEffectExecutor {
    public static final String TYPE = "showcase.outbox.write";
    public static final String ID = "showcase-outbox";
    private final Path directory;
    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    ShowcaseOutboxExecutor(Path directory) {
        this.directory = directory;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> effectTypes() {
        return Set.of(TYPE);
    }

    @Override
    public boolean supports(String effectType) {
        return TYPE.equals(effectType);
    }

    @Override
    public EffectExecution execute(EffectExecutionContext context, PendingEffect effect) {
        attempts.incrementAndGet();
        if (!TYPE.equals(effect.type()) || effect.idHash().length != 32
                || !MessageDigest.isEqual(effect.idHash(), effect.effectId().hash())) {
            failures.incrementAndGet();
            return EffectExecution.failed("INVALID_SHOWCASE_EFFECT", false);
        }
        String hash = HexFormat.of().formatHex(effect.idHash());
        byte[] document = document(context.chainId(), effect, hash);
        Path target = directory.resolve(hash + ".json");
        try {
            Files.createDirectories(directory);
            if (Files.exists(target)) {
                if (!MessageDigest.isEqual(Files.readAllBytes(target), document)) {
                    failures.incrementAndGet();
                    return EffectExecution.failed("OUTBOX_IDEMPOTENCY_CONFLICT", false);
                }
            } else {
                installAtomically(target, document);
            }
            successes.incrementAndGet();
            byte[] externalRef = ("showcase-outbox:" + hash).getBytes(StandardCharsets.US_ASCII);
            return EffectExecution.confirmed(externalRef, sha256(document));
        } catch (IOException failure) {
            failures.incrementAndGet();
            return EffectExecution.failed("OUTBOX_IO_RETRY", true);
        }
    }

    @Override
    public EffectExecutorOperationalSnapshot operationalSnapshot() {
        long total = attempts.get();
        long ok = successes.get();
        long failed = failures.get();
        return new EffectExecutorOperationalSnapshot(
                EffectExecutorOperationalSnapshot.Readiness.READY,
                total, ok, failed, 0, 0,
                ok == 0 ? EffectExecutorOperationalSnapshot.AgeBucket.NEVER
                        : EffectExecutorOperationalSnapshot.AgeBucket.LESS_THAN_ONE_MINUTE,
                failed == 0 ? EffectExecutorOperationalSnapshot.AgeBucket.NEVER
                        : EffectExecutorOperationalSnapshot.AgeBucket.LESS_THAN_ONE_MINUTE,
                failed == 0 ? EffectExecutorOperationalSnapshot.FailureCode.NONE
                        : EffectExecutorOperationalSnapshot.FailureCode.SERVICE_UNAVAILABLE);
    }

    private static byte[] document(String chainId, PendingEffect effect, String hash) {
        String json = "{\"schemaVersion\":1,\"chainId\":\"" + escape(chainId)
                + "\",\"effectId\":\"" + escape(effect.effectId().canonical())
                + "\",\"idHash\":\"" + hash + "\",\"type\":\"" + TYPE
                + "\",\"scope\":\"" + escape(effect.scope())
                + "\",\"payloadHex\":\"" + HexFormat.of().formatHex(effect.payload()) + "\"}\n";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static void installAtomically(Path target, byte[] document) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".showcase-", ".tmp");
        try {
            Files.write(temporary, document);
            try {
                Files.setPosixFilePermissions(temporary, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystems retain their platform defaults.
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target);
            }
        } catch (java.nio.file.FileAlreadyExistsException raced) {
            // Another retry installed the same deterministic target.
        } finally {
            Files.deleteIfExists(temporary);
        }
        if (!MessageDigest.isEqual(Files.readAllBytes(target), document)) {
            throw new IOException("outbox target differs from deterministic receipt");
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
