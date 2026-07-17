package com.bloxbean.cardano.yano.appchain.composite.contracts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** No-SPI strict canonical wire codec for {@code composite/aggregate-v1}. */
public final class AggregateQueryCodecV1 {
    public static final String PATH = "composite/aggregate-v1";
    public static final int VERSION = 1;
    private static final int MAX_TEXT_BYTES = 128;
    private static final Pattern COMPONENT_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    private AggregateQueryCodecV1() {
    }

    public record Subquery(String componentId, String localPath, byte[] params) {
        public Subquery {
            componentId = validateComponentId(componentId);
            localPath = route(localPath);
            params = Objects.requireNonNull(params, "params").clone();
        }
        @Override public byte[] params() { return params.clone(); }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Subquery value
                    && componentId.equals(value.componentId)
                    && localPath.equals(value.localPath)
                    && Arrays.equals(params, value.params);
        }
        @Override public int hashCode() {
            return 31 * Objects.hash(componentId, localPath) + Arrays.hashCode(params);
        }
    }

    public record Result(String componentId, String localPath, byte[] payload) {
        public Result {
            componentId = validateComponentId(componentId);
            localPath = route(localPath);
            payload = Objects.requireNonNull(payload, "payload").clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Result value
                    && componentId.equals(value.componentId)
                    && localPath.equals(value.localPath)
                    && Arrays.equals(payload, value.payload);
        }
        @Override public int hashCode() {
            return 31 * Objects.hash(componentId, localPath) + Arrays.hashCode(payload);
        }
    }

    public static byte[] encodeRequest(List<Subquery> queries, AggregateQueryLimitsV1 limits) {
        Objects.requireNonNull(queries, "queries");
        validateRequest(queries, limits);
        byte[] encoded = encode(queries.stream().map(query ->
                new Entry(query.componentId(), query.localPath(), query.params())).toList());
        if (encoded.length > AggregateQueryLimitsV1.HOST_MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("aggregate encoded request exceeds host transport bound");
        }
        return encoded;
    }

    public static List<Subquery> decodeRequest(byte[] encoded, AggregateQueryLimitsV1 limits) {
        List<Entry> entries = decode(encoded, limits.maxSubqueries(), limits.maxParameterBytes(),
                AggregateQueryLimitsV1.HOST_MAX_REQUEST_BYTES);
        List<Subquery> result = entries.stream()
                .map(entry -> new Subquery(entry.componentId(), entry.localPath(), entry.bytes()))
                .toList();
        validateRequest(result, limits);
        return result;
    }

    public static byte[] encodeResponse(List<Result> results, AggregateQueryLimitsV1 limits) {
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(limits, "limits");
        if (results.isEmpty() || results.size() > limits.maxSubqueries()) {
            throw new IllegalArgumentException(
                    "aggregate response result count is outside configured bounds");
        }
        long bytes = results.stream().mapToLong(result -> result.payload().length).sum();
        if (bytes > limits.maxResponseBytes()) {
            throw new IllegalArgumentException("aggregate response payload exceeds configured bound");
        }
        byte[] encoded = encode(results.stream().map(result ->
                new Entry(result.componentId(), result.localPath(), result.payload())).toList());
        if (encoded.length > limits.maxResponseBytes()) {
            throw new IllegalArgumentException("aggregate encoded response exceeds configured bound");
        }
        return encoded;
    }

    public static List<Result> decodeResponse(byte[] encoded, AggregateQueryLimitsV1 limits) {
        return decode(encoded, limits.maxSubqueries(), limits.maxResponseBytes(),
                limits.maxResponseBytes()).stream()
                .map(entry -> new Result(entry.componentId(), entry.localPath(), entry.bytes()))
                .toList();
    }

    private static void validateRequest(List<Subquery> queries, AggregateQueryLimitsV1 limits) {
        Objects.requireNonNull(limits, "limits");
        if (queries.isEmpty() || queries.size() > limits.maxSubqueries()) {
            throw new IllegalArgumentException("aggregate request subquery count is outside configured bounds");
        }
        long bytes = queries.stream().mapToLong(query -> query.params().length).sum();
        if (bytes > limits.maxParameterBytes()) {
            throw new IllegalArgumentException("aggregate request parameters exceed configured bound");
        }
    }

    private static byte[] encode(List<Entry> entries) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeInt(entries.size());
            for (Entry entry : entries) {
                writeString(out, entry.componentId());
                writeString(out, entry.localPath());
                out.writeInt(entry.bytes().length);
                out.write(entry.bytes());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory aggregate query encoding failed", impossible);
        }
    }

    private static List<Entry> decode(byte[] encoded, int maxEntries,
                                      int maxPayloadBytes, int maxEncodedBytes) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length > maxEncodedBytes) {
            throw new IllegalArgumentException("aggregate query encoding exceeds configured bound");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != VERSION) {
                throw new IllegalArgumentException("unsupported aggregate query version");
            }
            int count = in.readInt();
            if (count < 1 || count > maxEntries) {
                throw new IllegalArgumentException("aggregate query entry count is outside configured bounds");
            }
            List<Entry> entries = new ArrayList<>(count);
            long payloadBytes = 0;
            for (int index = 0; index < count; index++) {
                String componentId = readString(in);
                String path = readString(in);
                int length = in.readInt();
                if (length < 0 || length > in.available()) {
                    throw new IllegalArgumentException("invalid aggregate query payload length");
                }
                payloadBytes += length;
                if (payloadBytes > maxPayloadBytes) {
                    throw new IllegalArgumentException("aggregate query payload exceeds configured bound");
                }
                entries.add(new Entry(componentId, path, in.readNBytes(length)));
            }
            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing aggregate query bytes");
            }
            return List.copyOf(entries);
        } catch (IOException | RuntimeException malformed) {
            if (malformed instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IllegalArgumentException("invalid aggregate query encoding", malformed);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 1 || size > MAX_TEXT_BYTES || size > in.available()) {
            throw new IllegalArgumentException("invalid aggregate query string length");
        }
        byte[] bytes = in.readNBytes(size);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("aggregate query string is not canonical UTF-8");
        }
        return value;
    }

    private static String validateComponentId(String value) {
        if (value == null || !COMPONENT_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "componentId must match [a-z][a-z0-9-]{0,62}");
        }
        return value;
    }

    private static String route(String value) {
        if (value == null || value.startsWith("~")) {
            throw new IllegalArgumentException("localPath is invalid");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("localPath is invalid");
        }
        for (char character : value.toCharArray()) {
            if (character < 0x21 || character > 0x7e) {
                throw new IllegalArgumentException("localPath is invalid");
            }
        }
        return value;
    }

    private record Entry(String componentId, String localPath, byte[] bytes) {
    }
}
