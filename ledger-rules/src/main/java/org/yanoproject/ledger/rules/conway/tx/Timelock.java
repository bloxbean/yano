package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A native script ({@code Timelock}, Allegra/Scripts.hs), decoded from its original bytes as Haskell's
 * {@code TimelockRaw} decoder does ({@code Summands}, :235-245): {@code [0, keyhash]}, {@code [1, [scripts]]} all
 * of, {@code [2, [scripts]]} any of, {@code [3, n, [scripts]]} at least {@code n} ({@code Int}), {@code [4, slot]}
 * time start, {@code [5, slot]} time expire ({@code Word64}); anything else does not decode.
 */
public sealed interface Timelock {

    record RequireSignature(byte[] keyHash) implements Timelock {
        public RequireSignature {
            keyHash = keyHash.clone();
        }

        @Override
        public byte[] keyHash() {
            return keyHash.clone();
        }
    }

    record RequireAllOf(List<Timelock> scripts) implements Timelock {
        public RequireAllOf {
            scripts = List.copyOf(scripts);
        }
    }

    record RequireAnyOf(List<Timelock> scripts) implements Timelock {
        public RequireAnyOf {
            scripts = List.copyOf(scripts);
        }
    }

    record RequireMOf(long required, List<Timelock> scripts) implements Timelock {
        public RequireMOf {
            scripts = List.copyOf(scripts);
        }
    }

    record RequireTimeStart(BigInteger slot) implements Timelock {
    }

    record RequireTimeExpire(BigInteger slot) implements Timelock {
    }

    BigInteger INT64_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    BigInteger INT64_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /**
     * @param bytes the native script's encoding (one data item, nothing after it)
     * @throws TxDecodingException when Haskell would not decode it
     */
    static Timelock decode(byte[] bytes) {
        CborReader reader = new CborReader(bytes);
        Timelock script = read(reader);
        if (!reader.atEnd()) {
            throw new TxDecodingException("trailing bytes after a native script");
        }
        return script;
    }

    /** Reads one native script at the reader's position. */
    static Timelock read(CborReader reader) {
        long length = reader.readArrayHeader();
        long kind = reader.readUnsignedLong();
        Timelock script;
        long expected;
        switch ((int) Math.min(kind, 6)) {
            case 0 -> {
                byte[] hash = reader.readDefiniteBytes();
                if (hash.length != RawCredential.HASH_LENGTH) {
                    throw new TxDecodingException("a native script key hash is 28 bytes");
                }
                script = new RequireSignature(hash);
                expected = 2;
            }
            case 1 -> {
                script = new RequireAllOf(readList(reader));
                expected = 2;
            }
            case 2 -> {
                script = new RequireAnyOf(readList(reader));
                expected = 2;
            }
            case 3 -> {
                BigInteger n = reader.readInteger();
                if (n.compareTo(INT64_MIN) < 0 || n.compareTo(INT64_MAX) > 0) {
                    throw new TxDecodingException("native script m-of-n count out of Int range: " + n);
                }
                script = new RequireMOf(n.longValue(), readList(reader));
                expected = 3;
            }
            case 4 -> {
                script = new RequireTimeStart(reader.readUnsigned());
                expected = 2;
            }
            case 5 -> {
                script = new RequireTimeExpire(reader.readUnsigned());
                expected = 2;
            }
            default -> throw new TxDecodingException("unknown native script tag " + kind);
        }
        if (length != CborReader.INDEFINITE && length != expected) {
            throw new TxDecodingException("native script tag " + kind + " has " + length + " elements");
        }
        if (length == CborReader.INDEFINITE && reader.hasNext(length, expected)) {
            throw new TxDecodingException("native script tag " + kind + " has extra elements");
        }
        return script;
    }

    private static List<Timelock> readList(CborReader reader) {
        long count = reader.readArrayHeader();
        List<Timelock> scripts = new ArrayList<>();
        for (long i = 0; reader.hasNext(count, i); i++) {
            scripts.add(read(reader));
        }
        return scripts;
    }

    /**
     * {@code evalTimelock} (Allegra/Scripts.hs:477-497) with {@code validateTimelock}'s inputs (Allegra/Tx.hs:104-110):
     * the key hashes of the vkey witnesses only (not the bootstrap witnesses) and the transaction's validity
     * interval. A lower bound {@code s} holds when the transaction's lower bound is present and at least {@code s};
     * an upper bound {@code e} when the transaction's upper bound is present and at most {@code e}. {@code m}-of-n
     * stops once {@code m} scripts hold and holds for {@code m <= 0}.
     *
     * @param vkeyHashes the vkey witnesses' key hashes, hex
     * @param txStart    the transaction's lower bound, or null
     * @param txExpire   the transaction's upper bound, or null
     */
    static boolean evaluate(Timelock script, Set<String> vkeyHashes, BigInteger txStart, BigInteger txExpire) {
        return switch (script) {
            case RequireSignature s -> vkeyHashes.contains(HexUtil.encodeHexString(s.keyHash));
            case RequireAllOf all -> all.scripts.stream().allMatch(t -> evaluate(t, vkeyHashes, txStart, txExpire));
            case RequireAnyOf any -> any.scripts.stream().anyMatch(t -> evaluate(t, vkeyHashes, txStart, txExpire));
            case RequireMOf m -> {
                long needed = m.required;
                for (Timelock t : m.scripts) {
                    if (needed <= 0) {
                        break;
                    }
                    if (evaluate(t, vkeyHashes, txStart, txExpire)) {
                        needed--;
                    }
                }
                yield needed <= 0;
            }
            // lteNegInfty: lockStart <= txStart, false when the transaction has no lower bound
            case RequireTimeStart start -> txStart != null && start.slot.compareTo(txStart) <= 0;
            // ltePosInfty: txExp <= lockExp, false when the transaction has no upper bound
            case RequireTimeExpire expire -> txExpire != null && txExpire.compareTo(expire.slot) <= 0;
        };
    }
}
