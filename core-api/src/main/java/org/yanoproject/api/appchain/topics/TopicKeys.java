package org.yanoproject.api.appchain.topics;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Physical state keys of the topic registry under {@code ~yano/topics/v1/} (ADR app-layer/039 D2).
 * Names are hashed (BLAKE2b-256 of their UTF-8 bytes) so every key stays far below the 256-byte
 * proof-key bound whatever the name length; hashes and heights are raw bytes, heights and
 * revisions 8-byte big-endian.
 */
public final class TopicKeys {
    public static final String PREFIX = "~yano/topics/v1/";

    private static final byte[] CONFIG = ascii(PREFIX + "config");
    private static final byte[] COUNT = ascii(PREFIX + "count");
    private static final byte[] GOVERNANCE = ascii(PREFIX + "governance");
    private static final byte[] CONFIG_PENDING = ascii(PREFIX + "config-pending");
    /** Configuration revisions are kept in history under this reserved name (D1). */
    public static final String CONFIG_HISTORY_NAME = "~config";

    private TopicKeys() {
    }

    public static byte[] config() {
        return CONFIG.clone();
    }

    /** Total entries, pending creations included (the {@code max-entries} counter). */
    public static byte[] count() {
        return COUNT.clone();
    }

    /** The revision of the one configuration change awaiting activation, if any (D5, D13). */
    public static byte[] configPending() {
        return CONFIG_PENDING.clone();
    }

    /** The bounded list of governance approvals in progress. */
    public static byte[] governance() {
        return GOVERNANCE.clone();
    }

    public static byte[] entry(String name) {
        return key("entry/", nameHash(name));
    }

    public static byte[] queued(String name, long revision) {
        return key("queued/", nameHash(name), u64(revision));
    }

    public static byte[] history(String name, long revision) {
        return key("history/", nameHash(name), u64(revision));
    }

    public static byte[] schema(String irHashHex) {
        return key("schema/", HexUtil.decodeHexString(irHashHex));
    }

    public static byte[] used(String statementHashHex) {
        return key("used/", HexUtil.decodeHexString(statementHashHex));
    }

    public static byte[] revoked(String delegationHashHex) {
        return key("revoked/", HexUtil.decodeHexString(delegationHashHex));
    }

    /** How many revisions activate at {@code height} (materialized while applying block {@code height - 1}). */
    public static byte[] activationCount(long height) {
        return key("activation/", u64(height));
    }

    /** The {@code index}-th revision activating at {@code height}, in inclusion order. */
    public static byte[] activation(long height, long index) {
        return key("activation-item/", u64(height), u64(index));
    }

    public static byte[] receipt(String messageIdHex) {
        return key("receipt/", HexUtil.decodeHexString(messageIdHex));
    }

    /**
     * Expiry queues hold items of uniform retention, so one item never delays another's pruning:
     * {@link #CREDENTIAL_QUEUE} ({@code used/}, {@code revoked/}; at most {@code max-delegation-ms}) and
     * {@link #RECEIPT_QUEUE} (receipts; {@code max-command-ms}).
     */
    public static final int CREDENTIAL_QUEUE = 0;
    public static final int RECEIPT_QUEUE = 1;

    public static byte[] expiryBatch(int queue, long height) {
        return key("expiry/", new byte[] {(byte) queue}, u64(height));
    }

    public static byte[] expiryItem(int queue, long height, long index) {
        return key("expiry-item/", new byte[] {(byte) queue}, u64(height), u64(index));
    }

    public static byte[] expiryHead(int queue) {
        return key("expiry-head/", new byte[] {(byte) queue});
    }

    public static byte[] expiryTail(int queue) {
        return key("expiry-tail/", new byte[] {(byte) queue});
    }

    public static byte[] nameHash(String name) {
        return Blake2bUtil.blake2bHash256(name.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] key(String kind, byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii(PREFIX + kind));
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** 8-byte big-endian encoding used for heights, revisions and counters. */
    public static byte[] u64(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    public static long u64(byte[] bytes) {
        if (bytes.length != Long.BYTES) {
            throw new IllegalStateException("topic counter must be 8 bytes");
        }
        return ByteBuffer.wrap(bytes).getLong();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
