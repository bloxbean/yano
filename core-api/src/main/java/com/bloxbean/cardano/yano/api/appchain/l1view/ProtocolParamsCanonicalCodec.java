package com.bloxbean.cardano.yano.api.appchain.l1view;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot;
import com.bloxbean.cardano.yano.api.util.CostModelUtil;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Canonical named protocol-parameter document and typed field-leaf profile.
 *
 * <p>The document is {@code [version, epoch, [[field-id, type-tag, value], ...]]}; entries are
 * sorted by their lowercase ASCII field ID. A projected leaf is
 * {@code [leaf-version, epoch, field-id, type-tag, value]}. The state-machine consumes this
 * generic format and therefore does not need positional changes when the Cardano adapter adds a
 * field to the registry.</p>
 */
public final class ProtocolParamsCanonicalCodec {
    public static final int VERSION = 2;
    public static final int LEAF_VERSION = 1;
    public static final int MAX_DOCUMENT_BYTES = 6 * 1024 * 1024;

    public static final int TYPE_UNSIGNED = 0;
    public static final int TYPE_SIGNED = 1;
    public static final int TYPE_LOVELACE = 2;
    public static final int TYPE_RATIONAL = 3;
    public static final int TYPE_BYTES = 4;
    public static final int TYPE_PROTOCOL_VERSION = 5;
    public static final int TYPE_STRUCTURED = 6;

    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());
    private static final int MAX_FIELDS = 256;
    private static final int MAX_FIELD_ID_BYTES = 64;

    private ProtocolParamsCanonicalCodec() { }

    /** Encode the current Cardano adapter view into the generic named document. */
    public static byte[] encode(ProtocolParamsSnapshot value) {
        Objects.requireNonNull(value, "value");
        TreeMap<String, FieldValue> fields = new TreeMap<>();
        add(fields, "min-fee-a", TYPE_UNSIGNED, value.minFeeA());
        add(fields, "min-fee-b", TYPE_UNSIGNED, value.minFeeB());
        add(fields, "max-block-size", TYPE_UNSIGNED, value.maxBlockSize());
        add(fields, "max-tx-size", TYPE_UNSIGNED, value.maxTxSize());
        add(fields, "max-block-header-size", TYPE_UNSIGNED, value.maxBlockHeaderSize());
        add(fields, "key-deposit", TYPE_LOVELACE, value.keyDeposit());
        add(fields, "pool-deposit", TYPE_LOVELACE, value.poolDeposit());
        add(fields, "max-epoch", TYPE_UNSIGNED, value.eMax());
        add(fields, "desired-pool-count", TYPE_UNSIGNED, value.nOpt());
        add(fields, "pool-influence", TYPE_RATIONAL, rational(value.a0()));
        add(fields, "monetary-expansion", TYPE_RATIONAL, rational(value.rho()));
        add(fields, "treasury-expansion", TYPE_RATIONAL, rational(value.tau()));
        add(fields, "decentralisation-parameter", TYPE_RATIONAL,
                rational(value.decentralisationParam()));
        add(fields, "extra-entropy", TYPE_STRUCTURED, value.extraEntropy());
        if (value.protocolMajorVer() != null && value.protocolMinorVer() != null) {
            add(fields, "protocol-version", TYPE_PROTOCOL_VERSION,
                    List.of(value.protocolMajorVer(), value.protocolMinorVer()));
        }
        add(fields, "min-utxo", TYPE_LOVELACE, value.minUtxo());
        add(fields, "min-pool-cost", TYPE_LOVELACE, value.minPoolCost());
        add(fields, "nonce", TYPE_STRUCTURED, value.nonce());
        Map<String, List<Long>> costModels = compactCostModels(value);
        add(fields, "cost-models", TYPE_STRUCTURED, costModels);
        if (costModels != null) {
            costModels.forEach((language, model) -> {
                String normalizedLanguage = normalizedLanguage(language);
                add(fields, "cost-model-hash-" + normalizedLanguage, TYPE_BYTES,
                        Blake2bUtil.blake2bHash256(concat(
                                "yano:cardano-cost-model:v1:".getBytes(StandardCharsets.US_ASCII),
                                normalizedLanguage.getBytes(StandardCharsets.US_ASCII),
                                new byte[]{0}, encodeCanonical(model))));
            });
        }
        if (value.priceMem() != null && value.priceStep() != null) {
            add(fields, "execution-unit-prices", TYPE_STRUCTURED,
                    List.of(rational(value.priceMem()), rational(value.priceStep())));
        }
        if (value.maxTxExMem() != null && value.maxTxExSteps() != null) {
            add(fields, "max-tx-ex-units", TYPE_STRUCTURED,
                    List.of(value.maxTxExMem(), value.maxTxExSteps()));
        }
        if (value.maxBlockExMem() != null && value.maxBlockExSteps() != null) {
            add(fields, "max-block-ex-units", TYPE_STRUCTURED,
                    List.of(value.maxBlockExMem(), value.maxBlockExSteps()));
        }
        add(fields, "max-value-size", TYPE_UNSIGNED, value.maxValSize());
        add(fields, "collateral-percent", TYPE_UNSIGNED, value.collateralPercent());
        add(fields, "max-collateral-inputs", TYPE_UNSIGNED, value.maxCollateralInputs());
        add(fields, "coins-per-utxo-byte", TYPE_LOVELACE, value.coinsPerUtxoSize());
        add(fields, "coins-per-utxo-word", TYPE_LOVELACE, value.coinsPerUtxoWord());
        add(fields, "pool-voting-thresholds", TYPE_STRUCTURED, values(
                value.pvtMotionNoConfidence(), value.pvtCommitteeNormal(),
                value.pvtCommitteeNoConfidence(), value.pvtHardForkInitiation(),
                value.pvtPPSecurityGroup()));
        add(fields, "drep-voting-thresholds", TYPE_STRUCTURED, values(
                value.dvtMotionNoConfidence(), value.dvtCommitteeNormal(),
                value.dvtCommitteeNoConfidence(), value.dvtUpdateToConstitution(),
                value.dvtHardForkInitiation(), value.dvtPPNetworkGroup(),
                value.dvtPPEconomicGroup(), value.dvtPPTechnicalGroup(),
                value.dvtPPGovGroup(), value.dvtTreasuryWithdrawal()));
        add(fields, "committee-min-size", TYPE_UNSIGNED, value.committeeMinSize());
        add(fields, "committee-max-term-length", TYPE_UNSIGNED, value.committeeMaxTermLength());
        add(fields, "governance-action-lifetime", TYPE_UNSIGNED, value.govActionLifetime());
        add(fields, "governance-action-deposit", TYPE_LOVELACE, value.govActionDeposit());
        add(fields, "drep-deposit", TYPE_LOVELACE, value.drepDeposit());
        add(fields, "drep-activity", TYPE_UNSIGNED, value.drepActivity());
        add(fields, "min-fee-reference-script-cost-per-byte", TYPE_RATIONAL,
                rational(value.minFeeRefScriptCostPerByte()));

        List<Object> entries = fields.entrySet().stream()
                .map(entry -> (Object) List.of(entry.getKey(), entry.getValue().type(),
                        entry.getValue().value()))
                .toList();
        byte[] encoded = encodeCanonical(List.of(VERSION, value.epoch(), entries));
        if (encoded.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Canonical protocol parameters exceed the limit");
        }
        return encoded;
    }

    /** Strictly validate, decode, and re-encode a named document. */
    public static Document decode(long expectedEpoch, byte[] canonical) {
        if (expectedEpoch < 0 || canonical == null || canonical.length == 0
                || canonical.length > MAX_DOCUMENT_BYTES) throw invalid();
        try {
            List<?> document = CBOR.readValue(canonical, List.class);
            if (document.size() != 3 || uint(document.get(0)) != VERSION
                    || uint(document.get(1)) != expectedEpoch
                    || !(document.get(2) instanceof List<?> entries)
                    || entries.size() > MAX_FIELDS) throw invalid();
            ArrayList<Field> fields = new ArrayList<>(entries.size());
            String previous = null;
            for (Object item : entries) {
                if (!(item instanceof List<?> entry) || entry.size() != 3
                        || !(entry.get(0) instanceof String id)) throw invalid();
                validateFieldId(id);
                if (previous != null && previous.compareTo(id) >= 0) throw invalid();
                int type = Math.toIntExact(uint(entry.get(1)));
                Object normalized = normalize(type, entry.get(2));
                byte[] leaf = encodeLeaf(expectedEpoch, id, type, normalized);
                fields.add(new Field(id, type, normalized, leaf));
                previous = id;
            }
            List<Object> normalizedEntries = fields.stream().map(field -> (Object) List.of(
                    field.id(), field.type(), field.value())).toList();
            if (!Arrays.equals(canonical, encodeCanonical(
                    List.of(VERSION, expectedEpoch, normalizedEntries)))) throw invalid();
            return new Document(expectedEpoch, fields, canonical);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid canonical protocol parameters", failure);
        }
    }

    public static byte[] validate(long expectedEpoch, byte[] canonical) {
        return decode(expectedEpoch, canonical).canonicalCbor();
    }

    public static Optional<Field> field(long epoch, byte[] canonical, String fieldId) {
        validateFieldId(fieldId);
        return decode(epoch, canonical).fields().stream()
                .filter(field -> field.id().equals(fieldId)).findFirst();
    }

    public static byte[] encodeLeaf(long epoch, String id, int type, Object value) {
        if (epoch < 0) throw invalid();
        validateFieldId(id);
        Object normalized = normalize(type, value);
        return encodeCanonical(List.of(LEAF_VERSION, epoch, id, type, normalized));
    }

    public static Field decodeLeaf(byte[] canonical) {
        if (canonical == null || canonical.length == 0 || canonical.length > 64 * 1024) throw invalid();
        try {
            List<?> leaf = CBOR.readValue(canonical, List.class);
            if (leaf.size() != 5 || uint(leaf.get(0)) != LEAF_VERSION
                    || !(leaf.get(2) instanceof String id)) throw invalid();
            long epoch = uint(leaf.get(1));
            validateFieldId(id);
            int type = Math.toIntExact(uint(leaf.get(3)));
            Object value = normalize(type, leaf.get(4));
            byte[] normalized = encodeLeaf(epoch, id, type, value);
            if (!Arrays.equals(canonical, normalized)) throw invalid();
            return new Field(id, type, value, normalized);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid protocol-parameter field leaf", failure);
        }
    }

    public static String typeName(int type) {
        return switch (type) {
            case TYPE_UNSIGNED -> "unsigned-integer";
            case TYPE_SIGNED -> "signed-integer";
            case TYPE_LOVELACE -> "lovelace";
            case TYPE_RATIONAL -> "rational";
            case TYPE_BYTES -> "bytes";
            case TYPE_PROTOCOL_VERSION -> "protocol-version";
            case TYPE_STRUCTURED -> "structured";
            default -> throw invalid();
        };
    }

    public record Document(long epoch, List<Field> fields, byte[] canonicalCbor) {
        public Document {
            fields = List.copyOf(fields);
            canonicalCbor = canonicalCbor.clone();
        }
        @Override public byte[] canonicalCbor() { return canonicalCbor.clone(); }
    }

    public record Field(String id, int type, Object value, byte[] canonicalCbor) {
        public Field {
            value = immutable(value);
            canonicalCbor = canonicalCbor.clone();
        }
        @Override public byte[] canonicalCbor() { return canonicalCbor.clone(); }
        public String typeName() { return ProtocolParamsCanonicalCodec.typeName(type); }
    }

    private record FieldValue(int type, Object value) { }

    private static void add(Map<String, FieldValue> fields, String id, int type, Object value) {
        if (value == null || value instanceof List<?> list && list.stream().allMatch(Objects::isNull)) return;
        validateFieldId(id);
        fields.put(id, new FieldValue(type, normalize(type, value)));
    }

    private static Object normalize(int type, Object value) {
        return switch (type) {
            case TYPE_UNSIGNED, TYPE_LOVELACE -> nonnegative(value);
            case TYPE_SIGNED -> integer(value);
            case TYPE_RATIONAL -> rationalValue(value);
            case TYPE_BYTES -> {
                if (!(value instanceof byte[] bytes) || bytes.length == 0 || bytes.length > 64) throw invalid();
                yield bytes.clone();
            }
            case TYPE_PROTOCOL_VERSION -> {
                if (!(value instanceof List<?> list) || list.size() != 2) throw invalid();
                yield List.of(nonnegative(list.get(0)), nonnegative(list.get(1)));
            }
            case TYPE_STRUCTURED -> immutableStructured(value, 0);
            default -> throw invalid();
        };
    }

    private static Object immutableStructured(Object value, int depth) {
        if (depth > 8 || value == null) throw invalid();
        if (value instanceof String text) {
            if (text.length() > 16_384) throw invalid();
            return text;
        }
        if (value instanceof byte[] bytes) return bytes.clone();
        if (value instanceof Number) return integer(value);
        if (value instanceof List<?> list) {
            if (list.size() > 2_048) throw invalid();
            return list.stream().map(item -> immutableStructured(item, depth + 1)).toList();
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 64) throw invalid();
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) throw invalid();
                sorted.put(text, immutableStructured(item, depth + 1));
            });
            return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
        }
        throw invalid();
    }

    private static Object immutable(Object value) {
        if (value instanceof byte[] bytes) return bytes.clone();
        if (value instanceof List<?> list) return List.copyOf(list);
        if (value instanceof Map<?, ?> map) return Collections.unmodifiableMap(new LinkedHashMap<>(map));
        return value;
    }

    private static BigInteger nonnegative(Object value) {
        BigInteger number = integer(value);
        if (number.signum() < 0) throw invalid();
        return number;
    }

    private static BigInteger integer(Object value) {
        if (value instanceof BigInteger number) return number;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) return BigInteger.valueOf(((Number) value).longValue());
        throw invalid();
    }

    private static List<BigInteger> rationalValue(Object value) {
        if (!(value instanceof List<?> pair) || pair.size() != 2) throw invalid();
        BigInteger numerator = integer(pair.get(0));
        BigInteger denominator = nonnegative(pair.get(1));
        if (denominator.signum() == 0 || !numerator.gcd(denominator).equals(BigInteger.ONE)) throw invalid();
        return List.of(numerator, denominator);
    }

    private static List<BigInteger> rational(BigDecimal value) {
        if (value == null) return null;
        BigDecimal normalized = value.stripTrailingZeros();
        BigInteger numerator = normalized.unscaledValue();
        int scale = normalized.scale();
        BigInteger denominator = BigInteger.ONE;
        if (scale < 0) numerator = numerator.multiply(BigInteger.TEN.pow(-scale));
        else denominator = BigInteger.TEN.pow(scale);
        BigInteger divisor = numerator.gcd(denominator);
        return List.of(numerator.divide(divisor), denominator.divide(divisor));
    }

    private static List<Object> values(BigDecimal... values) {
        if (Arrays.stream(values).anyMatch(Objects::isNull)) return null;
        ArrayList<Object> result = new ArrayList<>(values.length);
        for (BigDecimal value : values) result.add(rational(value));
        return result;
    }

    private static byte[] concat(byte[]... values) {
        int size = Arrays.stream(values).mapToInt(value -> value.length).sum();
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static Map<String, List<Long>> compactCostModels(ProtocolParamsSnapshot value) {
        Map<String, List<Long>> raw = value.costModelsRaw();
        if (raw == null || raw.isEmpty()) raw = CostModelUtil.canonicalRawCostModelsTyped(value.costModels());
        return raw == null || raw.isEmpty() ? null : Collections.unmodifiableMap(new TreeMap<>(raw));
    }

    private static String normalizedLanguage(String language) {
        String normalized = language.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (normalized.isEmpty()) throw invalid();
        return normalized;
    }

    private static long uint(Object value) {
        BigInteger integer = nonnegative(value);
        if (integer.bitLength() > 63) throw invalid();
        return integer.longValueExact();
    }

    private static void validateFieldId(String id) {
        if (id == null || id.isEmpty() || id.getBytes(StandardCharsets.US_ASCII).length > MAX_FIELD_ID_BYTES
                || !id.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw invalid();
    }

    private static byte[] encodeCanonical(Object value) {
        try {
            return CBOR.writeValueAsBytes(value);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Unable to encode canonical protocol parameters", failure);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid canonical protocol parameters");
    }
}
