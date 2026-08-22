package com.bloxbean.cardano.yano.archive.api;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire form for one already-materialised archive row, used to carry artifact rows from the
 * reader to the sink.
 *
 * <p>The artifact wire form is the <strong>final</strong> row, not the source record it was
 * derived from. The sink cannot derive a row itself: values such as the boundary block hash and
 * block time are not carried on {@link com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef},
 * and a sink implementation must reach artifacts only through the reader interface - it may not
 * depend on ledger state to fill them in. Materialising on the reader side also keeps the
 * projection byte-identical to the replay worker, because both build the row through the same
 * shared builder.
 *
 * <p>The encoding is tagged per value rather than schema-driven, so a column-order mistake
 * surfaces as a type mismatch at decode instead of as silently transposed data.
 */
public final class ArchiveRowCodec {

    private static final byte NULL = 0;
    private static final byte INT = 1;
    private static final byte LONG = 2;
    private static final byte STRING = 3;
    private static final byte BYTES = 4;
    private static final byte UUID_TAG = 5;
    private static final byte BOOL = 6;

    private ArchiveRowCodec() {}

    /** Encode a whole row, table name included, so the sink cannot mis-route it. */
    public static byte[] encode(ArchiveRow row) {
        Objects.requireNonNull(row, "row");
        var out = new ByteArrayOutputStream(160);
        writeBytes(out, row.table().getBytes(StandardCharsets.UTF_8));
        List<Object> values = row.values();
        writeInt(out, values.size());
        for (Object value : values) {
            switch (value) {
                case null -> out.write(NULL);
                case Integer i -> { out.write(INT); writeInt(out, i); }
                case Long l -> { out.write(LONG); writeLong(out, l); }
                case Boolean b -> { out.write(BOOL); out.write(b ? 1 : 0); }
                case String s -> { out.write(STRING); writeBytes(out, s.getBytes(StandardCharsets.UTF_8)); }
                case byte[] b -> { out.write(BYTES); writeBytes(out, b); }
                case UUID u -> {
                    out.write(UUID_TAG);
                    writeLong(out, u.getMostSignificantBits());
                    writeLong(out, u.getLeastSignificantBits());
                }
                default -> throw new IllegalArgumentException(
                        "unsupported archive row value type " + value.getClass().getName());
            }
        }
        return out.toByteArray();
    }

    public static ArchiveRow decode(byte[] encoded) {
        ByteBuffer in = ByteBuffer.wrap(Objects.requireNonNull(encoded, "encoded"));
        String table = new String(readBytes(in), StandardCharsets.UTF_8);
        int count = in.getInt();
        List<Object> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte tag = in.get();
            values.add(switch (tag) {
                case NULL -> null;
                case INT -> in.getInt();
                case LONG -> in.getLong();
                case BOOL -> in.get() != 0;
                case STRING -> new String(readBytes(in), StandardCharsets.UTF_8);
                case BYTES -> readBytes(in);
                case UUID_TAG -> new UUID(in.getLong(), in.getLong());
                default -> throw new IllegalArgumentException("unknown archive row value tag " + tag);
            });
        }
        return new ArchiveRow(table, values);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(ByteBuffer.allocate(Integer.BYTES).putInt(value).array(), 0, Integer.BYTES);
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        out.write(ByteBuffer.allocate(Long.BYTES).putLong(value).array(), 0, Long.BYTES);
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] value) {
        writeInt(out, value.length);
        out.write(value, 0, value.length);
    }

    private static byte[] readBytes(ByteBuffer in) {
        byte[] value = new byte[in.getInt()];
        in.get(value);
        return value;
    }
}
