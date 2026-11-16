package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborHead;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.transaction.raw.HaskellPolicy;
import com.bloxbean.cardano.client.transaction.spec.script.NativeScript;
import com.bloxbean.cardano.client.transaction.spec.script.NativeScriptEvaluator;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * A script with its original bytes: a native script (language 0, the {@code Timelock} encoding) or a Plutus script
 * (languages 1–3, the {@code PlutusBinary}: the contents of the script's CBOR byte string, itself CBOR-wrapped flat).
 *
 * <p>It is CCL's raw script view ({@link com.bloxbean.cardano.client.transaction.raw.RawScript}), checked the way
 * Haskell decodes a script: a Plutus script is a definite-length byte string; a native script decodes as Haskell's
 * {@code TimelockRaw} decoder does ({@code Summands}, Allegra/Scripts.hs:235-245: {@code [0, keyhash]},
 * {@code [1, [scripts]]}, {@code [2, [scripts]]}, {@code [3, n, [scripts]]}, {@code [4, slot]}, {@code [5, slot]}),
 * which CCL's native script decoder implements, with its key hashes definite-length byte strings. CCL's decoder also
 * reads Dijkstra's guard, type 6, in any era, and so does this class, so that sync can still hash a reference script
 * holding one; CCL's {@code HaskellPolicy} refuses it in a Conway transaction (ADR 0005 D3). The hash is CCL's,
 * from the original bytes; a native script is decoded and evaluated by CCL without recursion, so any nesting depth
 * works on any thread.</p>
 */
public final class RawScript {

    public static final int NATIVE = 0;
    public static final int PLUTUS_V1 = 1;
    public static final int PLUTUS_V2 = 2;
    public static final int PLUTUS_V3 = 3;

    private static final int MAJOR_UNSIGNED = 0;
    private static final int MAJOR_BYTES = 2;
    private static final int MAJOR_TEXT = 3;
    private static final int MAJOR_ARRAY = 4;
    private static final int MAJOR_TAG = 6;
    private static final int TAG_EMBEDDED = 24;
    private static final int BREAK = 0xff;

    private final com.bloxbean.cardano.client.transaction.raw.RawScript view;
    private final byte[] bytes;
    private final NativeScript nativeScript;
    private final byte[] hash;

    private RawScript(com.bloxbean.cardano.client.transaction.raw.RawScript view, byte[] bytes,
                      NativeScript nativeScript) {
        this.view = view;
        this.bytes = bytes;
        this.nativeScript = nativeScript;
        this.hash = view.hash();
    }

    /**
     * Reads a script as Haskell decodes it.
     *
     * @param language 0 native, 1–3 Plutus V1–V3
     * @param span     the native script, or the Plutus script's byte string, as encoded
     * @throws TxDecodingException when Haskell would not decode it
     */
    static RawScript of(int language, CborSpan span) {
        if (language < NATIVE || language > PLUTUS_V3) {
            throw new TxDecodingException("script language " + language + " is not a Conway language");
        }
        if (language != NATIVE) {
            definiteBytes(span);
        } else {
            requireDefiniteByteStrings(span);
        }
        return accepted(new com.bloxbean.cardano.client.transaction.raw.RawScript(language, span));
    }

    /**
     * A script {@link HaskellPolicy} has accepted: a Plutus script's bytes, or a native script decoded.
     *
     * @param view CCL's view of the script
     * @throws TxDecodingException when a native script does not decode
     */
    static RawScript accepted(com.bloxbean.cardano.client.transaction.raw.RawScript view) {
        CborSpan span = view.span();
        if (view.type() != NATIVE) {
            return new RawScript(view, span.byteString(), null);
        }
        try {
            return new RawScript(view, span.bytes(), (NativeScript) RawModels.toScript(view));
        } catch (CborDeserializationException | CborRuntimeException e) {
            throw new TxDecodingException("native script: " + e.getMessage(), e);
        }
    }

    /**
     * Reads a reference script, {@code script_ref = #6.24(bytes .cbor script)} with
     * {@code script = [0, native_script] / [1, plutus_v1] / [2, plutus_v2] / [3, plutus_v3]}: CCL's
     * {@code TransactionOutput#getScriptRef()} holds the inner {@code script} (optionally still tag-24 wrapped).
     *
     * @throws TxDecodingException when the bytes are not a Conway script
     */
    public static RawScript fromScriptRef(byte[] scriptRef) {
        CborSpan ref = span(scriptRef);
        if (major(ref) == MAJOR_TAG) {
            if (ref.tag() != TAG_EMBEDDED) {
                throw new TxDecodingException("a script reference is not in tag 24");
            }
            CborSpan embedded = ref.untag();
            definiteBytes(embedded);
            ref = span(embedded.byteString());
        }
        return readScript(ref);
    }

    /**
     * @param bytes one well-formed CBOR item
     * @return its span
     * @throws TxDecodingException otherwise: every {@code CborRuntimeException} of a script's reading is one
     *                             (ADR 0005 D7)
     */
    static CborSpan span(byte[] bytes) {
        try {
            return CborSpan.of(bytes);
        } catch (CborRuntimeException e) {
            throw new TxDecodingException(e.getMessage(), e);
        }
    }

    /** @return the major type of the item's first head: 6 for a tagged item */
    private static int major(CborSpan item) {
        return (item.buffer()[item.offset()] & 0xff) >>> 5;
    }

    /** An untagged, definite-length byte string, as {@code decodeBytes} reads one below decoder version 12. */
    private static void definiteBytes(CborSpan item) {
        if (major(item) != MAJOR_BYTES) {
            throw new TxDecodingException("expected a byte string at " + item.offset());
        }
        if (item.isIndefinite()) {
            throw new TxDecodingException("an indefinite-length byte string at " + item.offset()
                    + " (definite only below decoder version 12)");
        }
    }

    /** Reads {@code script = [language, script]}, the reader of that CDDL rule. */
    public static RawScript readScript(CborSpan script) {
        if (major(script) != MAJOR_ARRAY) {
            throw new TxDecodingException("a script is [language, script]");
        }
        List<CborSpan> fields;
        try {
            fields = script.items();
        } catch (CborRuntimeException e) {
            throw new TxDecodingException(e.getMessage(), e);
        }
        if (fields.size() != 2) {
            throw new TxDecodingException("a script is [language, script]");
        }
        CborSpan language = fields.get(0);
        if (major(language) != MAJOR_UNSIGNED) {
            throw new TxDecodingException("expected an unsigned integer at " + language.offset());
        }
        BigInteger value = language.asBigInteger();
        if (value.compareTo(BigInteger.valueOf(PLUTUS_V3)) > 0) {
            throw new TxDecodingException("script language " + value + " is not supported in Conway");
        }
        return of(value.intValue(), fields.get(1));
    }

    /**
     * Haskell's decoders take a native script's key hashes as definite-length byte strings below decoder version 12;
     * CCL's decoder joins the chunks of an indefinite one. A key hash is the only byte string a native script holds,
     * so one flat pass over the heads finds an indefinite one.
     */
    private static void requireDefiniteByteStrings(CborSpan script) {
        byte[] b = script.buffer();
        int pos = script.offset();
        int end = pos + script.length();
        try {
            while (pos < end) {
                if ((b[pos] & 0xff) == BREAK) {
                    pos++;
                    continue;
                }
                CborHead head = CborHead.at(b, pos, end);
                boolean string = head.major() == MAJOR_BYTES || head.major() == MAJOR_TEXT;
                if (head.major() == MAJOR_BYTES && head.isIndefinite()) {
                    throw new TxDecodingException("an indefinite-length byte string in a native script (definite "
                            + "only below decoder version 12)");
                }
                pos = string && !head.isIndefinite() ? head.stringEnd(end) : head.end();
            }
        } catch (CborRuntimeException e) {
            throw new TxDecodingException(e.getMessage(), e);
        }
    }

    /** @return 0 native, 1 PlutusV1, 2 PlutusV2, 3 PlutusV3 */
    public int language() {
        return view.type();
    }

    /** @return the native script's encoding, or the Plutus script's {@code PlutusBinary} */
    public byte[] bytes() {
        return bytes.clone();
    }

    public boolean isNative() {
        return language() == NATIVE;
    }

    public boolean isPlutus() {
        return language() != NATIVE;
    }

    /** @return {@code hashScript}: blake2b-224 of the language tag and the original bytes (CCL {@code RawScript}) */
    public byte[] hash() {
        return hash.clone();
    }

    public String hashHex() {
        return HexUtil.encodeHexString(hash);
    }

    /**
     * {@code evalTimelock} (Allegra/Scripts.hs:477-497) with {@code validateTimelock}'s inputs (Allegra/Tx.hs:104-110),
     * by CCL's {@link NativeScriptEvaluator}: the key hashes of the vkey witnesses only (not the bootstrap witnesses)
     * and the transaction's validity interval.
     *
     * @param vkeyHashes the vkey witnesses' key hashes, hex
     * @param txStart    the transaction's lower bound, or null
     * @param txExpire   the transaction's upper bound, or null
     */
    public boolean nativeScriptHolds(Set<String> vkeyHashes, BigInteger txStart, BigInteger txExpire) {
        if (!isNative()) {
            throw new IllegalStateException("not a native script");
        }
        return NativeScriptEvaluator.evaluate(nativeScript, vkeyHashes, slot(txStart), slot(txExpire));
    }

    /** A {@code Word64} slot as the evaluator's unsigned {@code long}. */
    private static Long slot(BigInteger slot) {
        return slot != null ? slot.longValue() : null;
    }

    /** @return {@code PlutusV1}, {@code PlutusV2}, {@code PlutusV3} or {@code Native} */
    public String languageName() {
        return isNative() ? "Native" : "PlutusV" + language();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RawScript other && language() == other.language() && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * language() + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return languageName() + " " + hashHex();
    }
}
