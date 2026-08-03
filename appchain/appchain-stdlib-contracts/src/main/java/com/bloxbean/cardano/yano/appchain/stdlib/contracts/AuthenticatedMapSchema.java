package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.SimpleValueType;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.CanonicalValueCbor;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.StdlibContractCbor;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical {@code yano-cbor-schema-ir-v1} contract and bounded evaluator.
 *
 * <p>The IR deliberately contains no references, recursion, host callbacks, or
 * implementation-defined nodes. Authoring tools resolve named CDDL rules before
 * producing these bytes. Runtime evaluation is a total predicate over one
 * canonical CBOR value.</p>
 */
public final class AuthenticatedMapSchema {
    public static final String CDDL_SUBSET_ID = "cddl-yano-subset-v1";
    public static final String IR_CATALOG_ID = "yano-cbor-schema-ir-v1";
    public static final int IR_VERSION = 1;

    public static final int MAX_SCHEMA_IR_BYTES = 65_536;
    public static final int MAX_SCHEMA_NODES = 2_048;
    public static final int MAX_SCHEMA_DEPTH = 32;
    public static final int MAX_CHOICE_OPTIONS = 32;
    public static final int MAX_MAP_FIELDS = 256;
    public static final int MAX_ARRAY_TERMS = 256;
    public static final int MAX_OCCURRENCE = 65_536;
    public static final int MAX_EVALUATION_STEPS = 262_144;

    public static final int INTEGER_UINT = 0;
    public static final int INTEGER_NINT = 1;
    public static final int INTEGER_ANY = 2;

    private static final int NODE_INTEGER = 0;
    private static final int NODE_TEXT = 1;
    private static final int NODE_BYTES = 2;
    private static final int NODE_BOOLEAN = 3;
    private static final int NODE_NULL = 4;
    private static final int NODE_CHOICE = 5;
    private static final int NODE_MAP = 6;
    private static final int NODE_ARRAY = 7;

    private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);
    public static final BigInteger MIN_CBOR_INTEGER = TWO_64.negate();
    public static final BigInteger MAX_CBOR_INTEGER = TWO_64.subtract(BigInteger.ONE);

    private AuthenticatedMapSchema() {
    }

    /** Construct and canonically encode one already-resolved schema tree. */
    public static Schema of(Node root) {
        Objects.requireNonNull(root, "root");
        requireBounded(root);
        byte[] definition = encodeDefinition(root);
        if (definition.length > MAX_SCHEMA_IR_BYTES) {
            throw invalid("schema IR exceeds " + MAX_SCHEMA_IR_BYTES + " bytes");
        }
        return new Schema(root, definition);
    }

    /** Strictly decode and re-freeze canonical schema IR bytes. */
    public static Schema decode(byte[] definition) {
        byte[] copy = definition == null ? null : definition.clone();
        if (!CanonicalValueCbor.accepts(copy, MAX_SCHEMA_IR_BYTES)) {
            throw invalid("schema IR is not canonical bounded CBOR");
        }
        try {
            CborDecoder decoder = new CborDecoder(new ByteArrayInputStream(copy));
            decoder.setMaxPreallocationSize(MAX_SCHEMA_IR_BYTES);
            List<DataItem> roots = decoder.decode();
            if (roots.size() != 1) {
                throw invalid("schema IR must contain one root");
            }
            List<DataItem> fields = arrayArity(roots.getFirst(), 2);
            if (uint(fields.get(0)) != IR_VERSION) {
                throw invalid("unsupported schema IR version");
            }
            DecodeBudget budget = new DecodeBudget();
            Node root = decodeNode(fields.get(1), 1, budget);
            requireBounded(root);
            if (!Arrays.equals(copy, encodeDefinition(root))) {
                throw invalid("schema IR is not in canonical node order");
            }
            return new Schema(root, copy);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception | StackOverflowError failure) {
            throw invalid("schema IR cannot be decoded");
        }
    }

    public static boolean accepts(byte[] definition, byte[] canonicalValue) {
        try {
            return decode(definition).accepts(canonicalValue);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    public static boolean isCanonicalDefinition(byte[] definition) {
        try {
            decode(definition);
            return true;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /** One startup-decoded immutable schema. */
    public record Schema(Node root, byte[] definition) {
        public Schema {
            root = Objects.requireNonNull(root, "root");
            definition = Objects.requireNonNull(definition, "definition").clone();
        }

        @Override
        public byte[] definition() {
            return definition.clone();
        }

        /** Total, bounded validation of exactly one canonical-CBOR item. */
        public boolean accepts(byte[] value) {
            if (!CanonicalValueCbor.accepts(
                    value, AuthenticatedMapContract.MAX_VALUE_BYTES)) {
                return false;
            }
            try {
                CborDecoder decoder = new CborDecoder(new ByteArrayInputStream(value));
                decoder.setMaxPreallocationSize(AuthenticatedMapContract.MAX_VALUE_BYTES);
                List<DataItem> roots = decoder.decode();
                return roots.size() == 1 && new Evaluation().matches(root, roots.getFirst());
            } catch (Exception | StackOverflowError failure) {
                return false;
            }
        }
    }

    public sealed interface Node permits IntegerNode, TextNode, BytesNode,
            BooleanNode, NullNode, ChoiceNode, MapNode, ArrayNode {
    }

    /** Integer domain plus inclusive bounds over the native CBOR integer range. */
    public record IntegerNode(int domain, BigInteger minimum, BigInteger maximum)
            implements Node {
        public IntegerNode {
            minimum = Objects.requireNonNull(minimum, "minimum");
            maximum = Objects.requireNonNull(maximum, "maximum");
            if (domain < INTEGER_UINT || domain > INTEGER_ANY
                    || minimum.compareTo(maximum) > 0
                    || minimum.compareTo(MIN_CBOR_INTEGER) < 0
                    || maximum.compareTo(MAX_CBOR_INTEGER) > 0
                    || domain == INTEGER_UINT && minimum.signum() < 0
                    || domain == INTEGER_NINT && maximum.signum() >= 0) {
                throw invalid("invalid schema integer domain or bounds");
            }
        }

        public static IntegerNode uint() {
            return new IntegerNode(INTEGER_UINT, BigInteger.ZERO, MAX_CBOR_INTEGER);
        }

        public static IntegerNode nint() {
            return new IntegerNode(INTEGER_NINT, MIN_CBOR_INTEGER,
                    BigInteger.valueOf(-1));
        }

        public static IntegerNode integer() {
            return new IntegerNode(INTEGER_ANY, MIN_CBOR_INTEGER,
                    MAX_CBOR_INTEGER);
        }
    }

    /** Text strings constrained by encoded UTF-8 byte length and optional literal. */
    public record TextNode(int minimumBytes, int maximumBytes, String literal)
            implements Node {
        public TextNode {
            requireSizeBounds(minimumBytes, maximumBytes, "text");
            if (literal != null) {
                if (!StandardCharsets.UTF_8.newEncoder().canEncode(literal)) {
                    throw invalid("text literal is not valid Unicode scalar text");
                }
                int bytes = literal.getBytes(StandardCharsets.UTF_8).length;
                if (bytes < minimumBytes || bytes > maximumBytes) {
                    throw invalid("text literal is outside schema size bounds");
                }
            }
        }

        public static TextNode any() {
            return new TextNode(0, AuthenticatedMapContract.MAX_VALUE_BYTES, null);
        }

        public static TextNode literal(String value) {
            Objects.requireNonNull(value, "value");
            int bytes = value.getBytes(StandardCharsets.UTF_8).length;
            return new TextNode(bytes, bytes, value);
        }
    }

    /** Byte strings constrained by length and optional literal. */
    public record BytesNode(int minimumBytes, int maximumBytes, byte[] literal)
            implements Node {
        public BytesNode {
            requireSizeBounds(minimumBytes, maximumBytes, "byte string");
            literal = literal == null ? null : literal.clone();
            if (literal != null
                    && (literal.length < minimumBytes || literal.length > maximumBytes)) {
                throw invalid("byte literal is outside schema size bounds");
            }
        }

        @Override
        public byte[] literal() {
            return literal == null ? null : literal.clone();
        }

        public static BytesNode any() {
            return new BytesNode(0, AuthenticatedMapContract.MAX_VALUE_BYTES, null);
        }

        public static BytesNode literal(byte[] value) {
            byte[] copy = Objects.requireNonNull(value, "value").clone();
            return new BytesNode(copy.length, copy.length, copy);
        }
    }

    /** Expected value: -1 for either boolean, 0 for false, 1 for true. */
    public record BooleanNode(int expected) implements Node {
        public BooleanNode {
            if (expected < -1 || expected > 1) {
                throw invalid("invalid schema boolean expectation");
            }
        }

        public static BooleanNode any() {
            return new BooleanNode(-1);
        }
    }

    public record NullNode() implements Node {
    }

    public record ChoiceNode(List<Node> options) implements Node {
        public ChoiceNode {
            options = List.copyOf(Objects.requireNonNull(options, "options"));
            if (options.size() < 2 || options.size() > MAX_CHOICE_OPTIONS
                    || options.stream().anyMatch(Objects::isNull)) {
                throw invalid("schema choice must contain 2-" + MAX_CHOICE_OPTIONS
                        + " options");
            }
        }
    }

    /** Exact text-keyed map field. Unknown fields are rejected. */
    public record MapField(String key, boolean required, Node value) {
        public MapField {
            key = Objects.requireNonNull(key, "key");
            value = Objects.requireNonNull(value, "value");
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(key)
                    || key.getBytes(StandardCharsets.UTF_8).length > 256) {
                throw invalid("schema map key exceeds 256 UTF-8 bytes");
            }
        }
    }

    public record MapNode(List<MapField> fields) implements Node {
        public MapNode {
            fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
            if (fields.size() > MAX_MAP_FIELDS || fields.stream().anyMatch(Objects::isNull)) {
                throw invalid("schema map exceeds field bound");
            }
            Set<String> keys = new HashSet<>();
            for (MapField field : fields) {
                if (!keys.add(field.key())) {
                    throw invalid("duplicate schema map key: " + field.key());
                }
            }
        }
    }

    public record Occurrence(int minimum, int maximum, Node value) {
        public Occurrence {
            value = Objects.requireNonNull(value, "value");
            if (minimum < 0 || maximum < minimum || maximum > MAX_OCCURRENCE) {
                throw invalid("invalid bounded schema occurrence");
            }
        }

        public static Occurrence required(Node value) {
            return new Occurrence(1, 1, value);
        }
    }

    public record ArrayNode(List<Occurrence> terms) implements Node {
        public ArrayNode {
            terms = List.copyOf(Objects.requireNonNull(terms, "terms"));
            if (terms.size() > MAX_ARRAY_TERMS || terms.stream().anyMatch(Objects::isNull)) {
                throw invalid("schema array exceeds term bound");
            }
        }
    }

    private static byte[] encodeDefinition(Node root) {
        Array wire = new Array();
        wire.add(new UnsignedInteger(IR_VERSION));
        wire.add(encodeNode(root));
        return StdlibContractCbor.encode(wire);
    }

    private static DataItem encodeNode(Node node) {
        Array wire = new Array();
        switch (node) {
            case IntegerNode integer -> {
                wire.add(new UnsignedInteger(NODE_INTEGER));
                wire.add(new UnsignedInteger(integer.domain()));
                wire.add(new UnicodeString(integer.minimum().toString()));
                wire.add(new UnicodeString(integer.maximum().toString()));
            }
            case TextNode text -> {
                wire.add(new UnsignedInteger(NODE_TEXT));
                wire.add(new UnsignedInteger(text.minimumBytes()));
                wire.add(new UnsignedInteger(text.maximumBytes()));
                wire.add(new UnsignedInteger(text.literal() == null ? 0 : 1));
                wire.add(new UnicodeString(text.literal() == null ? "" : text.literal()));
            }
            case BytesNode bytes -> {
                wire.add(new UnsignedInteger(NODE_BYTES));
                wire.add(new UnsignedInteger(bytes.minimumBytes()));
                wire.add(new UnsignedInteger(bytes.maximumBytes()));
                wire.add(new UnsignedInteger(bytes.literal() == null ? 0 : 1));
                wire.add(new ByteString(bytes.literal() == null
                        ? new byte[0] : bytes.literal()));
            }
            case BooleanNode bool -> {
                wire.add(new UnsignedInteger(NODE_BOOLEAN));
                wire.add(new UnsignedInteger(bool.expected() + 1));
            }
            case NullNode ignored -> wire.add(new UnsignedInteger(NODE_NULL));
            case ChoiceNode choice -> {
                wire.add(new UnsignedInteger(NODE_CHOICE));
                Array options = new Array();
                canonicalNodes(choice.options()).forEach(value -> options.add(encodeNode(value)));
                wire.add(options);
            }
            case MapNode map -> {
                wire.add(new UnsignedInteger(NODE_MAP));
                Array fields = new Array();
                canonicalFields(map.fields()).forEach(field -> {
                    Array encoded = new Array();
                    encoded.add(new UnicodeString(field.key()));
                    encoded.add(new UnsignedInteger(field.required() ? 1 : 0));
                    encoded.add(encodeNode(field.value()));
                    fields.add(encoded);
                });
                wire.add(fields);
            }
            case ArrayNode array -> {
                wire.add(new UnsignedInteger(NODE_ARRAY));
                Array terms = new Array();
                for (Occurrence term : array.terms()) {
                    Array encoded = new Array();
                    encoded.add(new UnsignedInteger(term.minimum()));
                    encoded.add(new UnsignedInteger(term.maximum()));
                    encoded.add(encodeNode(term.value()));
                    terms.add(encoded);
                }
                wire.add(terms);
            }
        }
        return wire;
    }

    private static Node decodeNode(DataItem item, int depth, DecodeBudget budget) {
        if (depth > MAX_SCHEMA_DEPTH || ++budget.nodes > MAX_SCHEMA_NODES) {
            throw invalid("schema IR exceeds structural bounds");
        }
        List<DataItem> fields = arrayArity(item, 1, 2, 4, 5);
        int kind = uint(fields.get(0));
        return switch (kind) {
            case NODE_INTEGER -> {
                requireArity(fields, 4);
                yield new IntegerNode(uint(fields.get(1)), integerText(fields.get(2)),
                        integerText(fields.get(3)));
            }
            case NODE_TEXT -> {
                requireArity(fields, 5);
                int present = flag(fields.get(3));
                String literal = text(fields.get(4));
                if (present == 0 && !literal.isEmpty()) {
                    throw invalid("absent text literal must be empty");
                }
                yield new TextNode(uint(fields.get(1)), uint(fields.get(2)),
                        present == 0 ? null : literal);
            }
            case NODE_BYTES -> {
                requireArity(fields, 5);
                int present = flag(fields.get(3));
                byte[] literal = bytes(fields.get(4));
                if (present == 0 && literal.length != 0) {
                    throw invalid("absent byte literal must be empty");
                }
                yield new BytesNode(uint(fields.get(1)), uint(fields.get(2)),
                        present == 0 ? null : literal);
            }
            case NODE_BOOLEAN -> {
                requireArity(fields, 2);
                int encoded = uint(fields.get(1));
                if (encoded > 2) throw invalid("invalid boolean schema node");
                yield new BooleanNode(encoded - 1);
            }
            case NODE_NULL -> {
                requireArity(fields, 1);
                yield new NullNode();
            }
            case NODE_CHOICE -> {
                requireArity(fields, 2);
                List<DataItem> options = boundedArray(fields.get(1), MAX_CHOICE_OPTIONS);
                List<Node> decoded = new ArrayList<>(options.size());
                for (DataItem option : options) {
                    decoded.add(decodeNode(option, depth + 1, budget));
                }
                yield new ChoiceNode(decoded);
            }
            case NODE_MAP -> {
                requireArity(fields, 2);
                List<DataItem> encodedFields = boundedArray(fields.get(1), MAX_MAP_FIELDS);
                List<MapField> decoded = new ArrayList<>(encodedFields.size());
                for (DataItem encoded : encodedFields) {
                    List<DataItem> field = arrayArity(encoded, 3);
                    decoded.add(new MapField(text(field.get(0)), flag(field.get(1)) == 1,
                            decodeNode(field.get(2), depth + 1, budget)));
                }
                yield new MapNode(decoded);
            }
            case NODE_ARRAY -> {
                requireArity(fields, 2);
                List<DataItem> encodedTerms = boundedArray(fields.get(1), MAX_ARRAY_TERMS);
                List<Occurrence> decoded = new ArrayList<>(encodedTerms.size());
                for (DataItem encoded : encodedTerms) {
                    List<DataItem> term = arrayArity(encoded, 3);
                    decoded.add(new Occurrence(uint(term.get(0)), uint(term.get(1)),
                            decodeNode(term.get(2), depth + 1, budget)));
                }
                yield new ArrayNode(decoded);
            }
            default -> throw invalid("unknown schema IR node " + kind);
        };
    }

    private static List<Node> canonicalNodes(List<Node> options) {
        List<Node> sorted = new ArrayList<>(options);
        sorted.sort(Comparator.comparing(AuthenticatedMapSchema::encodedForOrder,
                AuthenticatedMapSchema::compareCanonicalBytes));
        byte[] previous = null;
        for (Node option : sorted) {
            byte[] encoded = encodedForOrder(option);
            if (previous != null && Arrays.equals(previous, encoded)) {
                throw invalid("duplicate schema choice option");
            }
            previous = encoded;
        }
        return List.copyOf(sorted);
    }

    private static List<MapField> canonicalFields(List<MapField> fields) {
        List<MapField> sorted = new ArrayList<>(fields);
        sorted.sort(Comparator.comparing(field -> encodedText(field.key()),
                AuthenticatedMapSchema::compareCanonicalBytes));
        return List.copyOf(sorted);
    }

    private static byte[] encodedForOrder(Node node) {
        return StdlibContractCbor.encode(encodeNode(node));
    }

    private static byte[] encodedText(String value) {
        return StdlibContractCbor.encode(new UnicodeString(value));
    }

    private static int compareCanonicalBytes(byte[] left, byte[] right) {
        int sharedLength = Math.min(left.length, right.length);
        for (int index = 0; index < sharedLength; index++) {
            int comparison = Integer.compare(left[index] & 0xff, right[index] & 0xff);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.length, right.length);
    }

    private static void requireBounded(Node root) {
        BoundBudget budget = new BoundBudget();
        requireBounded(root, 1, budget);
    }

    private static void requireBounded(Node node, int depth, BoundBudget budget) {
        if (depth > MAX_SCHEMA_DEPTH || ++budget.nodes > MAX_SCHEMA_NODES) {
            throw invalid("schema exceeds node or depth bounds");
        }
        switch (node) {
            case ChoiceNode choice -> choice.options().forEach(
                    child -> requireBounded(child, depth + 1, budget));
            case MapNode map -> map.fields().forEach(
                    field -> requireBounded(field.value(), depth + 1, budget));
            case ArrayNode array -> array.terms().forEach(
                    term -> requireBounded(term.value(), depth + 1, budget));
            default -> {
            }
        }
    }

    private static final class Evaluation {
        private int steps;

        private boolean matches(Node schema, DataItem value) {
            if (++steps > MAX_EVALUATION_STEPS) return false;
            return switch (schema) {
                case IntegerNode integer -> matchesInteger(integer, value);
                case TextNode text -> matchesText(text, value);
                case BytesNode bytes -> matchesBytes(bytes, value);
                case BooleanNode bool -> matchesBoolean(bool, value);
                case NullNode ignored -> value instanceof SimpleValue simple
                        && simple.getSimpleValueType() == SimpleValueType.NULL;
                case ChoiceNode choice -> choice.options().stream()
                        .anyMatch(option -> matches(option, value));
                case MapNode map -> matchesMap(map, value);
                case ArrayNode array -> matchesArray(array, value);
            };
        }

        private boolean matchesInteger(IntegerNode schema, DataItem value) {
            BigInteger integer;
            if (value instanceof UnsignedInteger unsigned) {
                if (schema.domain() == INTEGER_NINT) return false;
                integer = unsigned.getValue();
            } else if (value instanceof NegativeInteger negative) {
                if (schema.domain() == INTEGER_UINT) return false;
                integer = negative.getValue();
            } else {
                return false;
            }
            return integer.compareTo(schema.minimum()) >= 0
                    && integer.compareTo(schema.maximum()) <= 0;
        }

        private boolean matchesText(TextNode schema, DataItem value) {
            if (!(value instanceof UnicodeString text)) return false;
            String decoded = text.getString();
            int bytes = decoded.getBytes(StandardCharsets.UTF_8).length;
            return bytes >= schema.minimumBytes() && bytes <= schema.maximumBytes()
                    && (schema.literal() == null || schema.literal().equals(decoded));
        }

        private boolean matchesBytes(BytesNode schema, DataItem value) {
            if (!(value instanceof ByteString bytes)) return false;
            byte[] decoded = bytes.getBytes();
            return decoded.length >= schema.minimumBytes()
                    && decoded.length <= schema.maximumBytes()
                    && (schema.literal() == null || Arrays.equals(schema.literal(), decoded));
        }

        private boolean matchesBoolean(BooleanNode schema, DataItem value) {
            if (!(value instanceof SimpleValue simple)) return false;
            int decoded = switch (simple.getSimpleValueType()) {
                case FALSE -> 0;
                case TRUE -> 1;
                default -> -2;
            };
            return decoded >= 0 && (schema.expected() < 0 || schema.expected() == decoded);
        }

        private boolean matchesMap(MapNode schema, DataItem value) {
            if (!(value instanceof co.nstant.in.cbor.model.Map map)
                    || map.getKeys().size() > schema.fields().size()) {
                return false;
            }
            for (DataItem key : map.getKeys()) {
                if (!(key instanceof UnicodeString)) return false;
            }
            int present = 0;
            for (MapField field : schema.fields()) {
                DataItem child = map.get(new UnicodeString(field.key()));
                if (child == null) {
                    if (field.required()) return false;
                } else {
                    present++;
                    if (!matches(field.value(), child)) return false;
                }
            }
            return present == map.getKeys().size();
        }

        private boolean matchesArray(ArrayNode schema, DataItem value) {
            if (!(value instanceof Array array)) return false;
            List<DataItem> items = array.getDataItems();
            return arrayTerms(schema.terms(), items, 0, 0, new HashMap<>());
        }

        private boolean arrayTerms(
                List<Occurrence> terms,
                List<DataItem> items,
                int termIndex,
                int itemIndex,
                java.util.Map<Long, Boolean> memo
        ) {
            if (++steps > MAX_EVALUATION_STEPS) return false;
            if (termIndex == terms.size()) return itemIndex == items.size();
            long key = (long) termIndex << 32 | itemIndex & 0xffff_ffffL;
            Boolean known = memo.get(key);
            if (known != null) return known;

            Occurrence term = terms.get(termIndex);
            int available = Math.min(term.maximum(), items.size() - itemIndex);
            int matched = 0;
            while (matched < available
                    && matches(term.value(), items.get(itemIndex + matched))) {
                matched++;
            }
            boolean accepted = false;
            for (int count = matched; count >= term.minimum(); count--) {
                if (++steps > MAX_EVALUATION_STEPS) break;
                if (arrayTerms(terms, items, termIndex + 1,
                        itemIndex + count, memo)) {
                    accepted = true;
                    break;
                }
            }
            memo.put(key, accepted);
            return accepted;
        }
    }

    private static List<DataItem> arrayArity(DataItem item, int... allowedArities) {
        if (!(item instanceof Array array) || array.isChunked()) {
            throw invalid("schema IR node must be an array");
        }
        List<DataItem> values = array.getDataItems();
        for (int arity : allowedArities) {
            if (values.size() == arity) return values;
        }
        throw invalid("schema IR node has invalid arity");
    }

    private static List<DataItem> boundedArray(DataItem item, int maximumItems) {
        if (!(item instanceof Array array) || array.isChunked()
                || array.getDataItems().size() > maximumItems) {
            throw invalid("schema IR collection exceeds its bound");
        }
        return array.getDataItems();
    }

    private static void requireArity(List<DataItem> fields, int arity) {
        if (fields.size() != arity) throw invalid("schema IR node has invalid arity");
    }

    private static int uint(DataItem item) {
        if (!(item instanceof UnsignedInteger integer)) {
            throw invalid("schema IR integer must be unsigned");
        }
        try {
            return integer.getValue().intValueExact();
        } catch (ArithmeticException failure) {
            throw invalid("schema IR integer exceeds range");
        }
    }

    private static int flag(DataItem item) {
        int value = uint(item);
        if (value > 1) throw invalid("schema IR flag is invalid");
        return value;
    }

    private static String text(DataItem item) {
        if (!(item instanceof UnicodeString text) || text.isChunked()) {
            throw invalid("schema IR text is invalid");
        }
        return text.getString();
    }

    private static byte[] bytes(DataItem item) {
        if (!(item instanceof ByteString bytes) || bytes.isChunked()) {
            throw invalid("schema IR bytes are invalid");
        }
        return bytes.getBytes().clone();
    }

    private static BigInteger integerText(DataItem item) {
        String value = text(item);
        try {
            BigInteger parsed = new BigInteger(value);
            if (!parsed.toString().equals(value)) {
                throw invalid("schema integer text is not canonical");
            }
            return parsed;
        } catch (NumberFormatException malformed) {
            throw invalid("schema integer text is malformed");
        }
    }

    private static void requireSizeBounds(int minimum, int maximum, String name) {
        if (minimum < 0 || maximum < minimum
                || maximum > AuthenticatedMapContract.MAX_VALUE_BYTES) {
            throw invalid("invalid " + name + " size bounds");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static final class DecodeBudget {
        private int nodes;
    }

    private static final class BoundBudget {
        private int nodes;
    }
}
