package org.yanoproject.ledger.rules.conway.utxow;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import org.yanoproject.ledger.rules.conway.tx.Hashes;
import org.yanoproject.ledger.rules.conway.tx.RawScript;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The script integrity hash ({@code ScriptIntegrity}, {@code mkScriptIntegrity}, {@code hashScriptIntegrity},
 * Alonzo/Tx.hs:377-423; language views, Alonzo/PParams.hs:545-600).
 *
 * <p>{@code mkScriptIntegrity pp tx langs} is absent when the transaction has no redeemers, no datums and
 * {@code langs} is empty; otherwise its preimage is the concatenation of</p>
 * <ol>
 *   <li>the redeemers' <b>original</b> bytes (witness set key 5), or, when the field is absent, the memoised
 *       encoding of empty {@code Redeemers} at Conway's lowest protocol version 9, the empty map {@code a0};</li>
 *   <li>the datums' original bytes (witness set key 4), or nothing when there are none;</li>
 *   <li>{@code encodeLangViews}: a definite map of one {@code LangDepView} per language, sorted by the shortlex
 *       order of the encoded tags. PlutusV2/V3: tag {@code serialize lang} ({@code 01}/{@code 02}), value the cost
 *       model as a definite list of integers. PlutusV1 keeps Alonzo's quirk: the tag is serialised twice (a byte
 *       string holding {@code 00}: {@code 41 00}) and the value is a byte string holding the cost model encoded as
 *       an <em>indefinite</em> list. A language without a cost model has {@code null} ({@code f6}) as its cost
 *       model encoding.</li>
 * </ol>
 * <p>The hash is blake2b-256 of the preimage. {@code langs} is {@code plutusLanguagesUsed}: the languages of the
 * needed Plutus scripts that the transaction provides, as witnesses or as reference scripts
 * ({@code mkAlonzoStAnnTx}, Alonzo.hs:127-160). The cost models are the view's raw lists
 * ({@code costModelsValid (pp ^. ppCostModelsL)}, in the ledger's parameter order; see {@link #costModel}).</p>
 */
final class ScriptIntegrity {

    /** The memoised encoding of empty {@code Redeemers} at protocol version 9: an empty map. */
    private static final byte[] EMPTY_REDEEMERS = {(byte) 0xa0};

    private ScriptIntegrity() {
    }

    /**
     * @param languages the languages used (1–3)
     * @return the preimage, or empty when Haskell's {@code mkScriptIntegrity} is {@code SNothing}
     */
    static Optional<byte[]> preimage(RawTransaction raw, Set<Integer> languages, ProtocolParams params) {
        CborSpan redeemers = raw.rawTx().witnessField(RawTransaction.WITNESS_REDEEMERS).orElse(null);
        CborSpan datums = raw.rawTx().witnessField(RawTransaction.WITNESS_DATUMS).orElse(null);
        if (redeemers == null && datums == null && languages.isEmpty()) {
            return Optional.empty();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(redeemers != null ? redeemers.bytes() : EMPTY_REDEEMERS);
        if (datums != null) {
            out.writeBytes(datums.bytes());
        }
        out.writeBytes(languageViews(languages, params));
        return Optional.of(out.toByteArray());
    }

    /** @return blake2b-256 of the preimage */
    static byte[] hash(byte[] preimage) {
        return Hashes.blake2b256(preimage);
    }

    /** {@code encodeLangViews (Set.map (getLanguageView pp) langs)}. */
    static byte[] languageViews(Set<Integer> languages, ProtocolParams params) {
        List<byte[][]> views = new ArrayList<>();
        for (int language : languages) {
            views.add(languageView(language, params));
        }
        views.sort(Comparator.comparing((byte[][] v) -> v[0].length).thenComparing(v -> v[0], Arrays::compareUnsigned));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        head(out, 5, views.size());
        for (byte[][] view : views) {
            out.writeBytes(view[0]);
            out.writeBytes(view[1]);
        }
        return out.toByteArray();
    }

    /** {@code getLanguageView pp lang}: {tag bytes, parameter bytes}. */
    static byte[][] languageView(int language, ProtocolParams params) {
        Optional<long[]> costs = costModel(language, params);
        ByteArrayOutputStream model = new ByteArrayOutputStream();
        if (costs.isEmpty()) {
            model.write(0xf6);
        } else if (language == RawScript.PLUTUS_V1) {
            model.write(0x9f);
            for (long cost : costs.get()) {
                integer(model, cost);
            }
            model.write(0xff);
        } else {
            head(model, 4, costs.get().length);
            for (long cost : costs.get()) {
                integer(model, cost);
            }
        }
        byte[] modelBytes = model.toByteArray();
        byte[] langTag = encodeUnsigned(language - 1);
        if (language == RawScript.PLUTUS_V1) {
            return new byte[][]{byteString(langTag), byteString(modelBytes)};
        }
        return new byte[][]{langTag, modelBytes};
    }

    /**
     * The view's cost model for a language, from {@link ProtocolParams#getCostModelsRaw()} only (the
     * {@code LedgerView#protocolParams()} contract): the named map's order is not the ledger's, so it is never used.
     *
     * <p>A {@code null} raw map means the view did not supply the raw form: unavailable. An empty raw map means the
     * ledger has no cost model for any language (Haskell's {@code costModelsValid} is empty), so every language has
     * none and the script's {@code UTXOS.CollectErrors [NoCostModel]} decides; unless the named map has entries,
     * which means the raw form was dropped: unavailable.</p>
     *
     * @return the parameters, or empty when the language has no cost model (encoded {@code null})
     * @throws LedgerStateUnavailableException when the view carries no raw cost models ({@code null}), or carries
     *                                         cost models (or this language's) only in the named form
     */
    static Optional<long[]> costModel(int language, ProtocolParams params) {
        String name = "PlutusV" + language;
        Map<String, List<Long>> raw = params.getCostModelsRaw();
        if (raw == null) {
            throw new LedgerStateUnavailableException("raw cost models unavailable in the view's protocol parameters");
        }
        if (raw.isEmpty() && params.getCostModels() != null && !params.getCostModels().isEmpty()) {
            throw new LedgerStateUnavailableException("the view's protocol parameters carry cost models only in the "
                    + "named form");
        }
        List<Long> costs = raw.get(name);
        if (costs == null || costs.isEmpty()) {
            if (params.getCostModels() != null && params.getCostModels().get(name) != null
                    && !params.getCostModels().get(name).isEmpty()) {
                throw new LedgerStateUnavailableException("raw cost model of " + name + " unavailable");
            }
            return Optional.empty();
        }
        return Optional.of(costs.stream().mapToLong(Long::longValue).toArray());
    }

    private static byte[] byteString(byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        head(out, 2, content.length);
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static byte[] encodeUnsigned(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        head(out, 0, value);
        return out.toByteArray();
    }

    /** A canonical CBOR integer ({@code encCBOR} of an {@code Int64}). */
    private static void integer(ByteArrayOutputStream out, long value) {
        if (value >= 0) {
            head(out, 0, value);
        } else {
            head(out, 1, -1 - value);
        }
    }

    /** A canonical CBOR head for a non-negative argument. */
    private static void head(ByteArrayOutputStream out, int major, long value) {
        int type = major << 5;
        if (value < 24) {
            out.write(type | (int) value);
        } else if (value < 0x100) {
            out.write(type | 24);
            out.write((int) value);
        } else if (value < 0x10000) {
            out.write(type | 25);
            out.write((int) (value >> 8));
            out.write((int) value);
        } else if (value < 0x1_0000_0000L) {
            out.write(type | 26);
            for (int shift = 24; shift >= 0; shift -= 8) {
                out.write((int) (value >> shift));
            }
        } else {
            out.write(type | 27);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (value >> shift));
            }
        }
    }
}
