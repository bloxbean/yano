package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A canonical re-encoding of a CBOR item, used as the equality key of decoded values: two encodings Haskell decodes
 * to equal values give the same key. Definite lengths, shortest heads, byte and text strings with their chunks
 * joined, map entries sorted by key, a tag-258 set's elements sorted (the tag dropped), and a tag-30 rational reduced
 * (Haskell keeps {@code BoundedRatio} normalised). Untagged sets are sorted by the caller ({@link #set}).
 */
final class CborCanonical {

    private final byte[] data;
    private int pos;

    private CborCanonical(byte[] data, int pos) {
        this.data = data;
        this.pos = pos;
    }

    /** @return the canonical encoding of {@code item} */
    static byte[] of(CborSpan item) {
        CborCanonical c = new CborCanonical(item.buffer(), item.offset());
        byte[] out = c.item();
        c.requireEnd(item);
        return out;
    }

    /** @return the canonical encoding of an array or set item as a set: its elements' encodings sorted */
    static byte[] set(CborSpan item) {
        CborCanonical c = new CborCanonical(item.buffer(), item.offset());
        int head = c.peek();
        if (head >>> 5 == 6) {
            c.pos++;
            BigInteger tag = c.argument(head & 0x1f);
            if (!tag.equals(BigInteger.valueOf(258))) {
                throw new TxDecodingException("expected a set, found tag " + tag);
            }
        }
        byte[] out = c.sortedArray();
        c.requireEnd(item);
        return out;
    }

    private void requireEnd(CborSpan item) {
        if (pos != item.offset() + item.length()) {
            throw new TxDecodingException("CBOR item at " + item.offset() + " has trailing bytes");
        }
    }

    private int peek() {
        if (pos >= data.length) {
            throw new TxDecodingException("unexpected end of CBOR at " + pos);
        }
        return data[pos] & 0xff;
    }

    private byte[] item() {
        int head = peek();
        int major = head >>> 5;
        int info = head & 0x1f;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        switch (major) {
            case 0, 1 -> {
                pos++;
                head(out, major, argument(info));
            }
            case 2, 3 -> {
                byte[] content = string(major);
                head(out, major, BigInteger.valueOf(content.length));
                out.writeBytes(content);
            }
            case 4 -> {
                List<byte[]> items = array();
                head(out, 4, BigInteger.valueOf(items.size()));
                items.forEach(out::writeBytes);
            }
            case 5 -> {
                pos++;
                long n = length(info);
                List<byte[][]> entries = new ArrayList<>();
                for (long i = 0; more(n, i); i++) {
                    entries.add(new byte[][]{item(), item()});
                }
                entries.sort((a, b) -> Arrays.compareUnsigned(a[0], b[0]));
                head(out, 5, BigInteger.valueOf(entries.size()));
                entries.forEach(e -> {
                    out.writeBytes(e[0]);
                    out.writeBytes(e[1]);
                });
            }
            case 6 -> {
                pos++;
                BigInteger tag = argument(info);
                if (tag.equals(BigInteger.valueOf(258))) {
                    return sortedArray();
                }
                head(out, 6, tag);
                if (tag.equals(BigInteger.valueOf(30))) {
                    out.writeBytes(rational());
                } else {
                    out.writeBytes(item());
                }
            }
            default -> {
                int start = pos;
                pos++;
                if (info >= 24 && info <= 27) {
                    pos += 1 << (info - 24);
                }
                out.write(data, start, pos - start);
            }
        }
        return out.toByteArray();
    }

    /** A {@code [numerator, denominator]} reduced by their greatest common divisor. */
    private byte[] rational() {
        int start = pos;
        List<byte[]> items = array();
        if (items.size() == 2 && (items.get(0)[0] & 0xe0) == 0 && (items.get(1)[0] & 0xe0) == 0) {
            BigInteger n = new CborCanonical(items.get(0), 0).argumentOf();
            BigInteger d = new CborCanonical(items.get(1), 0).argumentOf();
            BigInteger g = n.gcd(d);
            if (g.signum() > 0) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                head(out, 4, BigInteger.TWO);
                head(out, 0, n.divide(g));
                head(out, 0, d.divide(g));
                return out.toByteArray();
            }
        }
        pos = start;
        return item();
    }

    private BigInteger argumentOf() {
        int head = peek();
        pos++;
        return argument(head & 0x1f);
    }

    private byte[] sortedArray() {
        List<byte[]> items = array();
        items.sort(Arrays::compareUnsigned);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        head(out, 4, BigInteger.valueOf(items.size()));
        items.forEach(out::writeBytes);
        return out.toByteArray();
    }

    private List<byte[]> array() {
        int head = peek();
        if (head >>> 5 != 4) {
            throw new TxDecodingException("expected an array at " + pos);
        }
        pos++;
        long n = length(head & 0x1f);
        List<byte[]> items = new ArrayList<>();
        for (long i = 0; more(n, i); i++) {
            items.add(item());
        }
        return items;
    }

    private byte[] string(int major) {
        int head = peek();
        pos++;
        int info = head & 0x1f;
        if (info != 31) {
            return bytes(argument(info));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (peek() != 0xff) {
            int chunk = peek();
            if (chunk >>> 5 != major || (chunk & 0x1f) == 31) {
                throw new TxDecodingException("bad indefinite string chunk at " + pos);
            }
            pos++;
            out.writeBytes(bytes(argument(chunk & 0x1f)));
        }
        pos++;
        return out.toByteArray();
    }

    private byte[] bytes(BigInteger length) {
        if (length.compareTo(BigInteger.valueOf(data.length - pos)) > 0) {
            throw new TxDecodingException("string runs past the input at " + pos);
        }
        byte[] out = Arrays.copyOfRange(data, pos, pos + length.intValue());
        pos += out.length;
        return out;
    }

    private long length(int info) {
        if (info == 31) {
            return -1;
        }
        BigInteger n = argument(info);
        if (n.bitLength() > 31) {
            throw new TxDecodingException("container too large at " + pos);
        }
        return n.longValue();
    }

    private boolean more(long n, long i) {
        if (n >= 0) {
            return i < n;
        }
        if (peek() == 0xff) {
            pos++;
            return false;
        }
        return true;
    }

    private BigInteger argument(int info) {
        if (info < 24) {
            return BigInteger.valueOf(info);
        }
        if (info > 27) {
            throw new TxDecodingException("unsupported CBOR additional information " + info + " at " + pos);
        }
        int n = 1 << (info - 24);
        if (pos + n > data.length) {
            throw new TxDecodingException("truncated CBOR head at " + pos);
        }
        BigInteger v = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            v = v.shiftLeft(8).or(BigInteger.valueOf(data[pos++] & 0xff));
        }
        return v;
    }

    private static void head(ByteArrayOutputStream out, int major, BigInteger value) {
        int type = major << 5;
        long v = value.longValue();
        if (value.bitLength() > 63) {
            out.write(type | 27);
            byte[] b = value.toByteArray();
            byte[] fixed = new byte[8];
            System.arraycopy(b, Math.max(0, b.length - 8), fixed, Math.max(0, 8 - b.length), Math.min(8, b.length));
            out.writeBytes(fixed);
        } else if (v < 24) {
            out.write(type | (int) v);
        } else if (v < 0x100) {
            out.write(type | 24);
            out.write((int) v);
        } else if (v < 0x10000) {
            out.write(type | 25);
            out.write((int) (v >> 8));
            out.write((int) v);
        } else if (v < 0x1_0000_0000L) {
            out.write(type | 26);
            for (int shift = 24; shift >= 0; shift -= 8) {
                out.write((int) (v >> shift));
            }
        } else {
            out.write(type | 27);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (v >> shift));
            }
        }
    }
}
