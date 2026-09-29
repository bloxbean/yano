package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.util.HexUtil;

import java.util.Arrays;
import java.util.Objects;

/**
 * A script with its original bytes: a native script (language 0, the {@code Timelock} encoding) or a Plutus script
 * (languages 1–3, the {@code PlutusBinary}: the contents of the script's CBOR byte string, itself CBOR-wrapped
 * flat).
 *
 * @param language 0 native, 1 PlutusV1, 2 PlutusV2, 3 PlutusV3
 * @param bytes    the original bytes
 */
public record RawScript(int language, byte[] bytes) {

    public static final int NATIVE = 0;
    public static final int PLUTUS_V1 = 1;
    public static final int PLUTUS_V2 = 2;
    public static final int PLUTUS_V3 = 3;

    public RawScript {
        Objects.requireNonNull(bytes, "bytes");
        if (language < NATIVE || language > PLUTUS_V3) {
            throw new TxDecodingException("script language " + language + " is not a Conway language");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public boolean isNative() {
        return language == NATIVE;
    }

    public boolean isPlutus() {
        return language != NATIVE;
    }

    /** @return {@code hashScript}: blake2b-224 of the language tag and the original bytes */
    public byte[] hash() {
        return Hashes.scriptHash(language, bytes);
    }

    public String hashHex() {
        return HexUtil.encodeHexString(hash());
    }

    /** @return the decoded native script */
    public Timelock timelock() {
        if (!isNative()) {
            throw new IllegalStateException("not a native script");
        }
        return Timelock.decode(bytes);
    }

    /** @return {@code PlutusV1}, {@code PlutusV2}, {@code PlutusV3} or {@code Native} */
    public String languageName() {
        return isNative() ? "Native" : "PlutusV" + language;
    }

    /**
     * Reads a reference script, {@code script_ref = #6.24(bytes .cbor script)} with
     * {@code script = [0, native_script] / [1, plutus_v1] / [2, plutus_v2] / [3, plutus_v3]}: CCL's
     * {@code TransactionOutput#getScriptRef()} holds the inner {@code script} (optionally still tag-24 wrapped).
     *
     * @throws TxDecodingException when the bytes are not a Conway script
     */
    public static RawScript fromScriptRef(byte[] scriptRef) {
        CborReader reader = new CborReader(scriptRef);
        if (reader.peekMajor() == 6) {
            if (reader.readTag() != 24) {
                throw new TxDecodingException("a script reference is wrapped in tag 24");
            }
            byte[] inner = reader.readBytes();
            if (!reader.atEnd()) {
                throw new TxDecodingException("trailing bytes after a script reference");
            }
            reader = new CborReader(inner);
        }
        RawScript script = readScript(reader);
        if (!reader.atEnd()) {
            throw new TxDecodingException("trailing bytes after a reference script");
        }
        return script;
    }

    /** Reads {@code [language, script]}. */
    static RawScript readScript(CborReader reader) {
        long length = reader.readArrayHeader();
        if (length != 2 && length != CborReader.INDEFINITE) {
            throw new TxDecodingException("a script is [language, script]");
        }
        long language = reader.readUnsignedLong();
        RawScript script;
        if (language == NATIVE) {
            script = new RawScript(NATIVE, reader.copy(reader.readItem()));
            Timelock.decode(script.bytes);
        } else if (language <= PLUTUS_V3) {
            script = new RawScript((int) language, reader.readBytes());
        } else {
            throw new TxDecodingException("script language " + language + " is not supported in Conway");
        }
        if (length == CborReader.INDEFINITE && reader.hasNext(length, 2)) {
            throw new TxDecodingException("a script is [language, script]");
        }
        return script;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RawScript other && language == other.language
                && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * language + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return languageName() + " " + hashHex();
    }
}
