package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema.ArrayNode;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema.BooleanNode;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema.BytesNode;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema.ChoiceNode;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema.IntegerNode;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema.MapField;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema.MapNode;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema.Node;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema.NullNode;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema.Occurrence;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema.TextNode;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Compile the closed {@code cddl-yano-subset-v1} into canonical schema IR. */
public final class AuthenticatedMapCddlCompiler {
    public static final int MAX_SOURCE_BYTES = 65_536;
    public static final int MAX_RULES = 128;
    public static final int MAX_TOKENS = 16_384;

    private AuthenticatedMapCddlCompiler() {
    }

    public static Compilation compile(String source) {
        return compile(source, "root");
    }

    public static Compilation compile(String source, String rootRule) {
        String checkedSource = Objects.requireNonNull(source, "source");
        String checkedRoot = requireIdentifier(rootRule, "root rule");
        if (checkedSource.isBlank()
                || checkedSource.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES
                || checkedSource.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(checkedSource)) {
            throw invalid("CDDL source must contain 1-" + MAX_SOURCE_BYTES
                    + " non-NUL UTF-8 bytes");
        }
        Map<String, Expr> rules = new Parser(new Lexer(checkedSource).tokens()).document();
        if (!rules.containsKey(checkedRoot)) {
            throw invalid("CDDL root rule is not declared: " + checkedRoot);
        }
        Node root = new Resolver(rules).resolve(checkedRoot);
        AuthenticatedMapSchema.Schema schema = AuthenticatedMapSchema.of(root);
        return new Compilation(
                AuthenticatedMapSchema.CDDL_SUBSET_ID,
                AuthenticatedMapSchema.IR_CATALOG_ID,
                checkedRoot,
                schema.definition());
    }

    public record Compilation(
            String authoringLanguage,
            String irCatalog,
            String rootRule,
            byte[] definition
    ) {
        public Compilation {
            definition = Objects.requireNonNull(definition, "definition").clone();
        }

        @Override
        public byte[] definition() {
            return definition.clone();
        }

        public AuthenticatedMapSchema.Schema schema() {
            return AuthenticatedMapSchema.decode(definition);
        }
    }

    private sealed interface Expr permits PrimitiveExpr, IntegerLiteralExpr,
            TextLiteralExpr, BytesLiteralExpr, BooleanLiteralExpr, NullExpr,
            ReferenceExpr, ChoiceExpr, MapExpr, ArrayExpr, ControlledExpr {
    }

    private enum Primitive {
        UINT, NINT, INT, TSTR, BSTR, BOOL
    }

    private record PrimitiveExpr(Primitive primitive) implements Expr {
    }

    private record IntegerLiteralExpr(BigInteger value) implements Expr {
    }

    private record TextLiteralExpr(String value) implements Expr {
    }

    private record BytesLiteralExpr(byte[] value) implements Expr {
        private BytesLiteralExpr {
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }
    }

    private record BooleanLiteralExpr(boolean value) implements Expr {
    }

    private record NullExpr() implements Expr {
    }

    private record ReferenceExpr(String name) implements Expr {
    }

    private record ChoiceExpr(List<Expr> options) implements Expr {
        private ChoiceExpr {
            options = List.copyOf(options);
        }
    }

    private record FieldExpr(String key, boolean required, Expr value) {
    }

    private record MapExpr(List<FieldExpr> fields) implements Expr {
        private MapExpr {
            fields = List.copyOf(fields);
        }
    }

    private record OccurrenceExpr(int minimum, int maximum, Expr value) {
    }

    private record ArrayExpr(List<OccurrenceExpr> terms) implements Expr {
        private ArrayExpr {
            terms = List.copyOf(terms);
        }
    }

    private enum ControlKind {
        SIZE, GE, GT, LE, LT, EQ
    }

    private record Control(ControlKind kind, BigInteger minimum, BigInteger maximum) {
    }

    private record ControlledExpr(Expr base, List<Control> controls) implements Expr {
        private ControlledExpr {
            controls = List.copyOf(controls);
        }
    }

    private static final class Resolver {
        private final Map<String, Expr> rules;
        private final Map<String, Node> resolved = new HashMap<>();
        private final Set<String> active = new HashSet<>();
        private int steps;

        private Resolver(Map<String, Expr> rules) {
            this.rules = rules;
        }

        private Node resolve(String rule) {
            Node known = resolved.get(rule);
            if (known != null) return known;
            if (!active.add(rule)) {
                throw invalid("recursive CDDL rules are not supported: " + rule);
            }
            Expr expression = rules.get(rule);
            if (expression == null) {
                throw invalid("unknown CDDL rule: " + rule);
            }
            Node node = resolve(expression);
            active.remove(rule);
            resolved.put(rule, node);
            return node;
        }

        private Node resolve(Expr expression) {
            if (++steps > AuthenticatedMapSchema.MAX_SCHEMA_NODES * 4) {
                throw invalid("CDDL expansion exceeds the compilation budget");
            }
            return switch (expression) {
                case PrimitiveExpr primitive -> primitive(primitive.primitive());
                case IntegerLiteralExpr integer -> integerLiteral(integer.value());
                case TextLiteralExpr text -> TextNode.literal(text.value());
                case BytesLiteralExpr bytes -> BytesNode.literal(bytes.value());
                case BooleanLiteralExpr bool -> new BooleanNode(bool.value() ? 1 : 0);
                case NullExpr ignored -> new NullNode();
                case ReferenceExpr reference -> resolve(reference.name());
                case ChoiceExpr choice -> resolveChoice(choice);
                case MapExpr map -> new MapNode(map.fields().stream()
                        .map(field -> new MapField(field.key(), field.required(),
                                resolve(field.value())))
                        .toList());
                case ArrayExpr array -> new ArrayNode(array.terms().stream()
                        .map(term -> new Occurrence(term.minimum(), term.maximum(),
                                resolve(term.value())))
                        .toList());
                case ControlledExpr controlled -> applyControls(
                        resolve(controlled.base()), controlled.controls());
            };
        }

        private Node resolveChoice(ChoiceExpr choice) {
            List<Node> options = new ArrayList<>();
            for (Expr expression : choice.options()) {
                Node resolvedOption = resolve(expression);
                if (resolvedOption instanceof ChoiceNode nested) {
                    options.addAll(nested.options());
                } else {
                    options.add(resolvedOption);
                }
            }
            return new ChoiceNode(options);
        }

        private static Node primitive(Primitive primitive) {
            return switch (primitive) {
                case UINT -> IntegerNode.uint();
                case NINT -> IntegerNode.nint();
                case INT -> IntegerNode.integer();
                case TSTR -> TextNode.any();
                case BSTR -> BytesNode.any();
                case BOOL -> BooleanNode.any();
            };
        }

        private static IntegerNode integerLiteral(BigInteger value) {
            int domain = value.signum() < 0
                    ? AuthenticatedMapSchema.INTEGER_NINT
                    : AuthenticatedMapSchema.INTEGER_UINT;
            return new IntegerNode(domain, value, value);
        }

        private static Node applyControls(Node initial, List<Control> controls) {
            Node current = initial;
            for (Control control : controls) {
                current = switch (control.kind()) {
                    case SIZE -> size(current, control);
                    case GE, GT, LE, LT, EQ -> integer(current, control);
                };
            }
            return current;
        }

        private static Node size(Node node, Control control) {
            int minimum = exactNonnegativeInt(control.minimum(), "size minimum");
            int maximum = exactNonnegativeInt(control.maximum(), "size maximum");
            if (node instanceof TextNode text) {
                return new TextNode(Math.max(text.minimumBytes(), minimum),
                        Math.min(text.maximumBytes(), maximum), text.literal());
            }
            if (node instanceof BytesNode bytes) {
                return new BytesNode(Math.max(bytes.minimumBytes(), minimum),
                        Math.min(bytes.maximumBytes(), maximum), bytes.literal());
            }
            throw invalid(".size applies only to tstr or bstr in "
                    + AuthenticatedMapSchema.CDDL_SUBSET_ID);
        }

        private static Node integer(Node node, Control control) {
            if (!(node instanceof IntegerNode integer)) {
                throw invalid("numeric controls apply only to integer types");
            }
            BigInteger minimum = integer.minimum();
            BigInteger maximum = integer.maximum();
            switch (control.kind()) {
                case GE -> minimum = minimum.max(control.minimum());
                case GT -> minimum = minimum.max(control.minimum().add(BigInteger.ONE));
                case LE -> maximum = maximum.min(control.maximum());
                case LT -> maximum = maximum.min(control.maximum().subtract(BigInteger.ONE));
                case EQ -> {
                    minimum = minimum.max(control.minimum());
                    maximum = maximum.min(control.maximum());
                }
                default -> throw invalid("unexpected integer control");
            }
            return new IntegerNode(integer.domain(), minimum, maximum);
        }
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int offset;

        private Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        private Map<String, Expr> document() {
            Map<String, Expr> rules = new LinkedHashMap<>();
            while (!at(TokenKind.EOF)) {
                String name = requireIdentifier(take(TokenKind.IDENT).text(), "rule");
                take(TokenKind.EQUALS);
                Expr previous = rules.putIfAbsent(name, type());
                if (previous != null) throw invalid("duplicate CDDL rule: " + name);
                if (rules.size() > MAX_RULES) {
                    throw invalid("CDDL source exceeds " + MAX_RULES + " rules");
                }
            }
            if (rules.isEmpty()) throw invalid("CDDL source declares no rules");
            return Map.copyOf(rules);
        }

        private Expr type() {
            List<Expr> options = new ArrayList<>();
            options.add(primaryWithControls());
            while (accept(TokenKind.SLASH)) options.add(primaryWithControls());
            return options.size() == 1 ? options.getFirst() : new ChoiceExpr(options);
        }

        private Expr primaryWithControls() {
            Expr base = primary();
            List<Control> controls = new ArrayList<>();
            while (at(TokenKind.CONTROL)) {
                String name = take(TokenKind.CONTROL).text();
                controls.add(control(name));
            }
            return controls.isEmpty() ? base : new ControlledExpr(base, controls);
        }

        private Expr primary() {
            if (accept(TokenKind.LPAREN)) {
                Expr nested = type();
                take(TokenKind.RPAREN);
                return nested;
            }
            if (accept(TokenKind.LBRACE)) return map();
            if (accept(TokenKind.LBRACKET)) return array();
            if (at(TokenKind.STRING)) {
                return new TextLiteralExpr(take(TokenKind.STRING).text());
            }
            if (at(TokenKind.BYTES)) {
                return new BytesLiteralExpr(HexFormat.of().parseHex(
                        take(TokenKind.BYTES).text()));
            }
            if (at(TokenKind.NUMBER)) {
                BigInteger first = number();
                if (accept(TokenKind.RANGE)) {
                    BigInteger last = number();
                    return new ControlledExpr(IntegerNodeExpr.any(), List.of(
                            new Control(ControlKind.GE, first, first),
                            new Control(ControlKind.LE, last, last)));
                }
                return new IntegerLiteralExpr(first);
            }
            String name = take(TokenKind.IDENT).text();
            return switch (name) {
                case "uint" -> new PrimitiveExpr(Primitive.UINT);
                case "nint" -> new PrimitiveExpr(Primitive.NINT);
                case "int" -> new PrimitiveExpr(Primitive.INT);
                case "tstr" -> new PrimitiveExpr(Primitive.TSTR);
                case "bstr" -> new PrimitiveExpr(Primitive.BSTR);
                case "bool" -> new PrimitiveExpr(Primitive.BOOL);
                case "true" -> new BooleanLiteralExpr(true);
                case "false" -> new BooleanLiteralExpr(false);
                case "null" -> new NullExpr();
                default -> new ReferenceExpr(requireIdentifier(name, "reference"));
            };
        }

        private Expr map() {
            List<FieldExpr> fields = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            if (accept(TokenKind.RBRACE)) return new MapExpr(fields);
            while (true) {
                boolean required = !accept(TokenKind.QUESTION);
                String key;
                if (at(TokenKind.STRING)) key = take(TokenKind.STRING).text();
                else key = requireIdentifier(take(TokenKind.IDENT).text(), "map key");
                if (!keys.add(key)) throw invalid("duplicate CDDL map key: " + key);
                take(TokenKind.COLON);
                fields.add(new FieldExpr(key, required, type()));
                if (accept(TokenKind.RBRACE)) break;
                take(TokenKind.COMMA);
                if (accept(TokenKind.RBRACE)) break;
            }
            if (fields.size() > AuthenticatedMapSchema.MAX_MAP_FIELDS) {
                throw invalid("CDDL map exceeds the field bound");
            }
            return new MapExpr(fields);
        }

        private Expr array() {
            List<OccurrenceExpr> terms = new ArrayList<>();
            if (accept(TokenKind.RBRACKET)) return new ArrayExpr(terms);
            while (true) {
                int minimum = 1;
                int maximum = 1;
                if (accept(TokenKind.QUESTION)) {
                    minimum = 0;
                    maximum = 1;
                } else if (at(TokenKind.NUMBER) && peek(1).kind() == TokenKind.STAR) {
                    minimum = exactNonnegativeInt(number(), "occurrence minimum");
                    take(TokenKind.STAR);
                    maximum = exactNonnegativeInt(number(), "occurrence maximum");
                }
                if (maximum < minimum
                        || maximum > AuthenticatedMapSchema.MAX_OCCURRENCE) {
                    throw invalid("CDDL array occurrence is outside the bound");
                }
                terms.add(new OccurrenceExpr(minimum, maximum, type()));
                if (accept(TokenKind.RBRACKET)) break;
                take(TokenKind.COMMA);
                if (accept(TokenKind.RBRACKET)) break;
            }
            if (terms.size() > AuthenticatedMapSchema.MAX_ARRAY_TERMS) {
                throw invalid("CDDL array exceeds the term bound");
            }
            return new ArrayExpr(terms);
        }

        private Control control(String name) {
            ControlKind kind = switch (name) {
                case "size" -> ControlKind.SIZE;
                case "ge" -> ControlKind.GE;
                case "gt" -> ControlKind.GT;
                case "le" -> ControlKind.LE;
                case "lt" -> ControlKind.LT;
                case "eq" -> ControlKind.EQ;
                default -> throw invalid("unsupported CDDL control: ." + name);
            };
            if (kind == ControlKind.SIZE && accept(TokenKind.LPAREN)) {
                BigInteger minimum = number();
                take(TokenKind.RANGE);
                BigInteger maximum = number();
                take(TokenKind.RPAREN);
                return new Control(kind, minimum, maximum);
            }
            BigInteger value = number();
            return new Control(kind, value, value);
        }

        private BigInteger number() {
            try {
                String value = take(TokenKind.NUMBER).text();
                BigInteger parsed = new BigInteger(value);
                if (!parsed.toString().equals(value)) {
                    throw invalid("CDDL integers must use canonical decimal spelling");
                }
                return parsed;
            } catch (NumberFormatException malformed) {
                throw invalid("CDDL integer is malformed");
            }
        }

        private boolean at(TokenKind kind) {
            return peek(0).kind() == kind;
        }

        private boolean accept(TokenKind kind) {
            if (!at(kind)) return false;
            offset++;
            return true;
        }

        private Token take(TokenKind kind) {
            Token token = peek(0);
            if (token.kind() != kind) {
                throw invalid("expected " + kind + " at character " + token.position()
                        + " but found " + token.kind());
            }
            offset++;
            return token;
        }

        private Token peek(int lookahead) {
            int index = Math.min(offset + lookahead, tokens.size() - 1);
            return tokens.get(index);
        }
    }

    /** Marker used to build a directly ranged integer expression. */
    private static final class IntegerNodeExpr {
        private IntegerNodeExpr() {
        }

        private static Expr any() {
            return new PrimitiveExpr(Primitive.INT);
        }
    }

    private enum TokenKind {
        IDENT, NUMBER, STRING, BYTES, CONTROL,
        EQUALS, LBRACE, RBRACE, LBRACKET, RBRACKET,
        LPAREN, RPAREN, COLON, COMMA, SLASH, QUESTION, STAR, RANGE, EOF
    }

    private record Token(TokenKind kind, String text, int position) {
    }

    private static final class Lexer {
        private final String source;
        private final List<Token> tokens = new ArrayList<>();
        private int offset;

        private Lexer(String source) {
            this.source = source;
        }

        private List<Token> tokens() {
            while (offset < source.length()) {
                char character = source.charAt(offset);
                if (Character.isWhitespace(character)) {
                    offset++;
                } else if (character == ';') {
                    comment();
                } else if (character == 'h' && offset + 1 < source.length()
                        && source.charAt(offset + 1) == '\'') {
                    bytes();
                } else if (Character.isLetter(character) || character == '_') {
                    identifier();
                } else if (Character.isDigit(character)
                        || character == '-' && offset + 1 < source.length()
                        && Character.isDigit(source.charAt(offset + 1))) {
                    number();
                } else if (character == '"') {
                    string();
                } else if (character == '.') {
                    dot();
                } else {
                    symbol(character);
                }
                if (tokens.size() > MAX_TOKENS) {
                    throw invalid("CDDL source exceeds " + MAX_TOKENS + " tokens");
                }
            }
            tokens.add(new Token(TokenKind.EOF, "", offset));
            return List.copyOf(tokens);
        }

        private void comment() {
            while (offset < source.length() && source.charAt(offset) != '\n') offset++;
        }

        private void identifier() {
            int start = offset++;
            while (offset < source.length()) {
                char character = source.charAt(offset);
                if (!Character.isLetterOrDigit(character)
                        && character != '_' && character != '-') break;
                offset++;
            }
            tokens.add(new Token(TokenKind.IDENT, source.substring(start, offset), start));
        }

        private void number() {
            int start = offset++;
            while (offset < source.length() && Character.isDigit(source.charAt(offset))) offset++;
            tokens.add(new Token(TokenKind.NUMBER, source.substring(start, offset), start));
        }

        private void bytes() {
            int start = offset;
            offset += 2;
            int content = offset;
            while (offset < source.length() && source.charAt(offset) != '\'') offset++;
            if (offset >= source.length()) throw invalid("unterminated CDDL byte literal");
            String hex = source.substring(content, offset);
            offset++;
            if ((hex.length() & 1) != 0 || !hex.matches("[0-9a-fA-F]*")) {
                throw invalid("CDDL byte literal must contain complete hexadecimal bytes");
            }
            tokens.add(new Token(TokenKind.BYTES, hex.toLowerCase(Locale.ROOT), start));
        }

        private void string() {
            int start = offset++;
            StringBuilder decoded = new StringBuilder();
            while (offset < source.length()) {
                char character = source.charAt(offset++);
                if (character == '"') {
                    tokens.add(new Token(TokenKind.STRING, decoded.toString(), start));
                    return;
                }
                if (character < 0x20) throw invalid("control character in CDDL string");
                if (character != '\\') {
                    decoded.append(character);
                    continue;
                }
                if (offset >= source.length()) throw invalid("unterminated CDDL escape");
                char escape = source.charAt(offset++);
                decoded.append(switch (escape) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> unicodeEscape();
                    default -> throw invalid("unsupported CDDL string escape");
                });
            }
            throw invalid("unterminated CDDL string");
        }

        private char unicodeEscape() {
            if (offset > source.length() - 4) throw invalid("truncated CDDL unicode escape");
            String hex = source.substring(offset, offset + 4);
            offset += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException malformed) {
                throw invalid("invalid CDDL unicode escape");
            }
        }

        private void dot() {
            int start = offset++;
            if (offset < source.length() && source.charAt(offset) == '.') {
                offset++;
                tokens.add(new Token(TokenKind.RANGE, "..", start));
                return;
            }
            int nameStart = offset;
            while (offset < source.length() && Character.isLetter(source.charAt(offset))) offset++;
            if (nameStart == offset) throw invalid("invalid CDDL control at character " + start);
            tokens.add(new Token(TokenKind.CONTROL,
                    source.substring(nameStart, offset), start));
        }

        private void symbol(char character) {
            TokenKind kind = switch (character) {
                case '=' -> TokenKind.EQUALS;
                case '{' -> TokenKind.LBRACE;
                case '}' -> TokenKind.RBRACE;
                case '[' -> TokenKind.LBRACKET;
                case ']' -> TokenKind.RBRACKET;
                case '(' -> TokenKind.LPAREN;
                case ')' -> TokenKind.RPAREN;
                case ':' -> TokenKind.COLON;
                case ',' -> TokenKind.COMMA;
                case '/' -> TokenKind.SLASH;
                case '?' -> TokenKind.QUESTION;
                case '*' -> TokenKind.STAR;
                default -> throw invalid("unsupported CDDL character at " + offset
                        + ": " + character);
            };
            tokens.add(new Token(kind, Character.toString(character), offset++));
        }
    }

    private static int exactNonnegativeInt(BigInteger value, String name) {
        if (value.signum() < 0) throw invalid(name + " must be nonnegative");
        try {
            return value.intValueExact();
        } catch (ArithmeticException overflow) {
            throw invalid(name + " exceeds integer range");
        }
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}")) {
            throw invalid("invalid CDDL " + name + " identifier");
        }
        return value;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
