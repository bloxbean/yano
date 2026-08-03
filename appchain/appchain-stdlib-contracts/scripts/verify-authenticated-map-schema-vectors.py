#!/usr/bin/env python3
"""Independent cddl-yano-subset-v1 compiler and schema-vector evaluator."""

import binascii
import sys


MAX_OCCURRENCE = 65536
MIN_INTEGER = -(1 << 64)
MAX_INTEGER = (1 << 64) - 1


def require(condition, message):
    if not condition:
        raise ValueError(message)


def properties(path):
    result = {}
    with open(path, "r", encoding="utf-8") as source:
        for raw in source:
            line = raw.strip()
            if line and not line.startswith("#"):
                key, value = line.split("=", 1)
                result[key] = value
    return result


def head(major, value):
    require(value >= 0, "negative CBOR argument")
    if value < 24:
        return bytes([(major << 5) | value])
    if value <= 0xff:
        return bytes([(major << 5) | 24, value])
    if value <= 0xffff:
        return bytes([(major << 5) | 25]) + value.to_bytes(2, "big")
    if value <= 0xffffffff:
        return bytes([(major << 5) | 26]) + value.to_bytes(4, "big")
    require(value <= MAX_INTEGER, "CBOR argument overflow")
    return bytes([(major << 5) | 27]) + value.to_bytes(8, "big")


def cbor_uint(value):
    return head(0, value)


def cbor_bytes(value):
    return head(2, len(value)) + value


def cbor_text(value):
    encoded = value.encode("utf-8")
    return head(3, len(encoded)) + encoded


def cbor_array(values):
    return head(4, len(values)) + b"".join(values)


def encode_node(node):
    kind = node[0]
    if kind == "int":
        return cbor_array([
            cbor_uint(0), cbor_uint(node[1]),
            cbor_text(str(node[2])), cbor_text(str(node[3]))])
    if kind == "text":
        literal = node[3]
        return cbor_array([
            cbor_uint(1), cbor_uint(node[1]), cbor_uint(node[2]),
            cbor_uint(0 if literal is None else 1),
            cbor_text("" if literal is None else literal)])
    if kind == "bytes":
        literal = node[3]
        return cbor_array([
            cbor_uint(2), cbor_uint(node[1]), cbor_uint(node[2]),
            cbor_uint(0 if literal is None else 1),
            cbor_bytes(b"" if literal is None else literal)])
    if kind == "bool":
        return cbor_array([cbor_uint(3), cbor_uint(node[1] + 1)])
    if kind == "null":
        return cbor_array([cbor_uint(4)])
    if kind == "choice":
        encoded = sorted(encode_node(option) for option in node[1])
        require(len(set(encoded)) == len(encoded), "duplicate choice")
        return cbor_array([cbor_uint(5), cbor_array(encoded)])
    if kind == "map":
        fields = sorted(node[1], key=lambda field: cbor_text(field[0]))
        encoded = [cbor_array([
            cbor_text(key), cbor_uint(1 if required else 0), encode_node(value)])
            for key, required, value in fields]
        return cbor_array([cbor_uint(6), cbor_array(encoded)])
    if kind == "array":
        terms = [cbor_array([cbor_uint(minimum), cbor_uint(maximum),
                             encode_node(value)])
                 for minimum, maximum, value in node[1]]
        return cbor_array([cbor_uint(7), cbor_array(terms)])
    raise ValueError("unknown schema node")


def encode_ir(root):
    return cbor_array([cbor_uint(1), encode_node(root)])


class Lexer(object):
    SYMBOLS = {
        "=": "EQUALS", "{": "LBRACE", "}": "RBRACE",
        "[": "LBRACKET", "]": "RBRACKET", "(": "LPAREN",
        ")": "RPAREN", ":": "COLON", ",": "COMMA", "/": "SLASH",
        "?": "QUESTION", "*": "STAR"
    }

    def __init__(self, source):
        self.source = source
        self.offset = 0
        self.result = []

    def tokens(self):
        while self.offset < len(self.source):
            char = self.source[self.offset]
            if char.isspace():
                self.offset += 1
            elif char == ";":
                while self.offset < len(self.source) and self.source[self.offset] != "\n":
                    self.offset += 1
            elif char == "h" and self.offset + 1 < len(self.source) \
                    and self.source[self.offset + 1] == "'":
                self.byte_string()
            elif char.isalpha() or char == "_":
                self.identifier()
            elif char.isdigit() or (char == "-" and self.offset + 1 < len(self.source)
                                    and self.source[self.offset + 1].isdigit()):
                self.number()
            elif char == '"':
                self.string()
            elif char == ".":
                self.dot()
            else:
                require(char in self.SYMBOLS, "unsupported CDDL character")
                self.result.append((self.SYMBOLS[char], char, self.offset))
                self.offset += 1
        self.result.append(("EOF", "", self.offset))
        return self.result

    def identifier(self):
        start = self.offset
        self.offset += 1
        while self.offset < len(self.source):
            char = self.source[self.offset]
            if not (char.isalnum() or char in "_-"):
                break
            self.offset += 1
        self.result.append(("IDENT", self.source[start:self.offset], start))

    def number(self):
        start = self.offset
        self.offset += 1
        while self.offset < len(self.source) and self.source[self.offset].isdigit():
            self.offset += 1
        self.result.append(("NUMBER", self.source[start:self.offset], start))

    def byte_string(self):
        start = self.offset
        self.offset += 2
        content = self.offset
        while self.offset < len(self.source) and self.source[self.offset] != "'":
            self.offset += 1
        require(self.offset < len(self.source), "unterminated byte literal")
        value = self.source[content:self.offset]
        self.offset += 1
        require(len(value) % 2 == 0, "odd byte literal")
        try:
            bytes.fromhex(value)
        except ValueError:
            raise ValueError("invalid byte literal")
        self.result.append(("BYTES", value.lower(), start))

    def string(self):
        start = self.offset
        self.offset += 1
        decoded = []
        escapes = {'"': '"', "\\": "\\", "n": "\n", "r": "\r", "t": "\t"}
        while self.offset < len(self.source):
            char = self.source[self.offset]
            self.offset += 1
            if char == '"':
                self.result.append(("STRING", "".join(decoded), start))
                return
            require(ord(char) >= 0x20, "control character in string")
            if char != "\\":
                decoded.append(char)
                continue
            require(self.offset < len(self.source), "unterminated escape")
            escape = self.source[self.offset]
            self.offset += 1
            if escape == "u":
                require(self.offset + 4 <= len(self.source), "truncated unicode escape")
                decoded.append(chr(int(self.source[self.offset:self.offset + 4], 16)))
                self.offset += 4
            else:
                require(escape in escapes, "unsupported string escape")
                decoded.append(escapes[escape])
        raise ValueError("unterminated string")

    def dot(self):
        start = self.offset
        self.offset += 1
        if self.offset < len(self.source) and self.source[self.offset] == ".":
            self.offset += 1
            self.result.append(("RANGE", "..", start))
            return
        name = self.offset
        while self.offset < len(self.source) and self.source[self.offset].isalpha():
            self.offset += 1
        require(name < self.offset, "invalid control")
        self.result.append(("CONTROL", self.source[name:self.offset], start))


class Parser(object):
    def __init__(self, tokens):
        self.tokens = tokens
        self.offset = 0

    def document(self):
        rules = {}
        while not self.at("EOF"):
            name = self.take("IDENT")[1]
            self.take("EQUALS")
            require(name not in rules, "duplicate rule")
            rules[name] = self.type()
        require(rules, "no rules")
        return rules

    def type(self):
        options = [self.primary_controls()]
        while self.accept("SLASH"):
            options.append(self.primary_controls())
        return options[0] if len(options) == 1 else ("choice", options)

    def primary_controls(self):
        base = self.primary()
        controls = []
        while self.at("CONTROL"):
            controls.append(self.control(self.take("CONTROL")[1]))
        return base if not controls else ("controlled", base, controls)

    def primary(self):
        if self.accept("LPAREN"):
            value = self.type()
            self.take("RPAREN")
            return value
        if self.accept("LBRACE"):
            return self.map()
        if self.accept("LBRACKET"):
            return self.array()
        if self.at("STRING"):
            return ("text-literal", self.take("STRING")[1])
        if self.at("BYTES"):
            return ("bytes-literal", bytes.fromhex(self.take("BYTES")[1]))
        if self.at("NUMBER"):
            first = self.number()
            if self.accept("RANGE"):
                last = self.number()
                return ("controlled", ("primitive", "int"),
                        [("ge", first, first), ("le", last, last)])
            return ("integer-literal", first)
        name = self.take("IDENT")[1]
        if name in ("uint", "nint", "int", "tstr", "bstr", "bool"):
            return ("primitive", name)
        if name == "true":
            return ("bool-literal", True)
        if name == "false":
            return ("bool-literal", False)
        if name == "null":
            return ("null",)
        return ("reference", name)

    def map(self):
        fields = []
        keys = set()
        if self.accept("RBRACE"):
            return ("map", fields)
        while True:
            required = not self.accept("QUESTION")
            token = self.take("STRING") if self.at("STRING") else self.take("IDENT")
            key = token[1]
            require(key not in keys, "duplicate map key")
            keys.add(key)
            self.take("COLON")
            fields.append((key, required, self.type()))
            if self.accept("RBRACE"):
                break
            self.take("COMMA")
            if self.accept("RBRACE"):
                break
        return ("map", fields)

    def array(self):
        terms = []
        if self.accept("RBRACKET"):
            return ("array", terms)
        while True:
            minimum, maximum = 1, 1
            if self.accept("QUESTION"):
                minimum, maximum = 0, 1
            elif self.at("NUMBER") and self.peek(1)[0] == "STAR":
                minimum = self.number()
                self.take("STAR")
                maximum = self.number()
            require(0 <= minimum <= maximum <= MAX_OCCURRENCE, "occurrence bound")
            terms.append((minimum, maximum, self.type()))
            if self.accept("RBRACKET"):
                break
            self.take("COMMA")
            if self.accept("RBRACKET"):
                break
        return ("array", terms)

    def control(self, name):
        require(name in ("size", "ge", "gt", "le", "lt", "eq"),
                "unsupported control")
        if name == "size" and self.accept("LPAREN"):
            minimum = self.number()
            self.take("RANGE")
            maximum = self.number()
            self.take("RPAREN")
            return (name, minimum, maximum)
        value = self.number()
        return (name, value, value)

    def number(self):
        value = self.take("NUMBER")[1]
        parsed = int(value)
        require(str(parsed) == value, "non-canonical decimal")
        return parsed

    def at(self, kind):
        return self.peek(0)[0] == kind

    def accept(self, kind):
        if not self.at(kind):
            return False
        self.offset += 1
        return True

    def take(self, kind):
        token = self.peek(0)
        require(token[0] == kind, "expected %s at %s, got %s" %
                (kind, token[2], token[0]))
        self.offset += 1
        return token

    def peek(self, ahead):
        return self.tokens[min(self.offset + ahead, len(self.tokens) - 1)]


class Resolver(object):
    def __init__(self, rules):
        self.rules = rules
        self.memo = {}
        self.active = set()

    def rule(self, name):
        if name in self.memo:
            return self.memo[name]
        require(name in self.rules, "unknown rule")
        require(name not in self.active, "recursive rule")
        self.active.add(name)
        value = self.expr(self.rules[name])
        self.active.remove(name)
        self.memo[name] = value
        return value

    def expr(self, expression):
        kind = expression[0]
        if kind == "primitive":
            return self.primitive(expression[1])
        if kind == "integer-literal":
            value = expression[1]
            self.integer_range(value, value)
            return ("int", 1 if value < 0 else 0, value, value)
        if kind == "text-literal":
            size = len(expression[1].encode("utf-8"))
            return ("text", size, size, expression[1])
        if kind == "bytes-literal":
            return ("bytes", len(expression[1]), len(expression[1]), expression[1])
        if kind == "bool-literal":
            return ("bool", 1 if expression[1] else 0)
        if kind == "null":
            return ("null",)
        if kind == "reference":
            return self.rule(expression[1])
        if kind == "choice":
            options = []
            for child in expression[1]:
                resolved = self.expr(child)
                options.extend(resolved[1] if resolved[0] == "choice" else [resolved])
            return ("choice", options)
        if kind == "map":
            return ("map", [(key, required, self.expr(value))
                            for key, required, value in expression[1]])
        if kind == "array":
            return ("array", [(minimum, maximum, self.expr(value))
                              for minimum, maximum, value in expression[1]])
        if kind == "controlled":
            value = self.expr(expression[1])
            for control in expression[2]:
                value = self.control(value, control)
            return value
        raise ValueError("unknown expression")

    @staticmethod
    def primitive(name):
        if name == "uint":
            return ("int", 0, 0, MAX_INTEGER)
        if name == "nint":
            return ("int", 1, MIN_INTEGER, -1)
        if name == "int":
            return ("int", 2, MIN_INTEGER, MAX_INTEGER)
        if name == "tstr":
            return ("text", 0, 1048576, None)
        if name == "bstr":
            return ("bytes", 0, 1048576, None)
        if name == "bool":
            return ("bool", -1)
        raise ValueError("unknown primitive")

    def control(self, node, control):
        kind, first, last = control
        if kind == "size":
            require(node[0] in ("text", "bytes"), "size control type")
            result = (node[0], max(node[1], first), min(node[2], last), node[3])
            require(0 <= result[1] <= result[2] <= 1048576, "size bounds")
            return result
        require(node[0] == "int", "numeric control type")
        minimum, maximum = node[2], node[3]
        if kind == "ge":
            minimum = max(minimum, first)
        elif kind == "gt":
            minimum = max(minimum, first + 1)
        elif kind == "le":
            maximum = min(maximum, last)
        elif kind == "lt":
            maximum = min(maximum, last - 1)
        elif kind == "eq":
            minimum, maximum = max(minimum, first), min(maximum, last)
        self.integer_range(minimum, maximum)
        if node[1] == 0:
            require(minimum >= 0, "uint bounds")
        if node[1] == 1:
            require(maximum < 0, "nint bounds")
        return ("int", node[1], minimum, maximum)

    @staticmethod
    def integer_range(minimum, maximum):
        require(MIN_INTEGER <= minimum <= maximum <= MAX_INTEGER, "integer bounds")


class CborReader(object):
    def __init__(self, data):
        self.data = data
        self.offset = 0

    def item(self):
        require(self.offset < len(self.data), "truncated CBOR")
        initial = self.data[self.offset]
        self.offset += 1
        major, additional = initial >> 5, initial & 31
        require(additional != 31, "indefinite CBOR")
        value = self.argument(additional)
        if major == 0:
            return value
        if major == 1:
            return -1 - value
        if major == 2:
            return self.take_bytes(value)
        if major == 3:
            return self.take_bytes(value).decode("utf-8")
        if major == 4:
            return [self.item() for _ in range(value)]
        if major == 5:
            pairs = []
            for _ in range(value):
                pairs.append((self.item(), self.item()))
            return ("map-value", pairs)
        if major == 7 and additional in (20, 21, 22):
            return {20: False, 21: True, 22: None}[additional]
        raise ValueError("unsupported vector CBOR")

    def argument(self, additional):
        if additional < 24:
            return additional
        width = {24: 1, 25: 2, 26: 4, 27: 8}.get(additional)
        require(width is not None, "bad CBOR argument")
        return int.from_bytes(self.take_bytes(width), "big")

    def take_bytes(self, length):
        require(self.offset + length <= len(self.data), "truncated CBOR bytes")
        value = self.data[self.offset:self.offset + length]
        self.offset += length
        return value


def decode_exact(data):
    reader = CborReader(data)
    value = reader.item()
    require(reader.offset == len(data), "trailing CBOR")
    return value


def matches(node, value):
    kind = node[0]
    if kind == "int":
        return type(value) is int and (node[1] != 0 or value >= 0) \
            and (node[1] != 1 or value < 0) and node[2] <= value <= node[3]
    if kind == "text":
        return isinstance(value, str) and node[1] <= len(value.encode("utf-8")) <= node[2] \
            and (node[3] is None or node[3] == value)
    if kind == "bytes":
        return isinstance(value, bytes) and node[1] <= len(value) <= node[2] \
            and (node[3] is None or node[3] == value)
    if kind == "bool":
        return type(value) is bool and (node[1] < 0 or node[1] == (1 if value else 0))
    if kind == "null":
        return value is None
    if kind == "choice":
        return any(matches(option, value) for option in node[1])
    if kind == "map":
        if not (isinstance(value, tuple) and value[0] == "map-value"):
            return False
        pairs = value[1]
        if any(not isinstance(key, str) for key, _ in pairs):
            return False
        actual = dict(pairs)
        if len(actual) != len(pairs):
            return False
        declared = dict((key, (required, child)) for key, required, child in node[1])
        if any(key not in declared for key in actual):
            return False
        for key, (required, child) in declared.items():
            if key not in actual:
                if required:
                    return False
            elif not matches(child, actual[key]):
                return False
        return True
    if kind == "array":
        return isinstance(value, list) and array_matches(node[1], value, 0, 0, {})
    raise ValueError("unknown evaluator node")


def array_matches(terms, values, term_index, value_index, memo):
    key = (term_index, value_index)
    if key in memo:
        return memo[key]
    if term_index == len(terms):
        return value_index == len(values)
    minimum, maximum, child = terms[term_index]
    available = min(maximum, len(values) - value_index)
    matched = 0
    while matched < available and matches(child, values[value_index + matched]):
        matched += 1
    result = any(array_matches(terms, values, term_index + 1,
                               value_index + count, memo)
                 for count in range(matched, minimum - 1, -1))
    memo[key] = result
    return result


def verify(values):
    require(values["schema.version"] == "1", "schema vector version")
    require(values["authoring.language"] == "cddl-yano-subset-v1", "authoring id")
    require(values["ir.catalog"] == "yano-cbor-schema-ir-v1", "IR id")
    names = sorted(key.split(".")[1] for key in values
                   if key.startswith("schema.") and key.endswith(".root"))
    require(names, "no schema vectors")
    for name in names:
        prefix = "schema.%s." % name
        source = bytes.fromhex(values[prefix + "source.hex"]).decode("utf-8")
        rules = Parser(Lexer(source).tokens()).document()
        root_name = values[prefix + "root"]
        require(root_name in rules, "missing independent root")
        root = Resolver(rules).rule(root_name)
        actual_ir = encode_ir(root).hex()
        require(actual_ir == values[prefix + "ir.hex"],
                "%s independently compiled IR" % name)
        for verdict in ("accept", "reject"):
            count = int(values[prefix + verdict + ".count"])
            for index in range(count):
                candidate = decode_exact(bytes.fromhex(
                    values[prefix + verdict + ".%d" % index]))
                actual = matches(root, candidate)
                require(actual == (verdict == "accept"),
                        "%s %s vector %d" % (name, verdict, index))
    print("Verified %d authenticated-map schema compiler/evaluator vector sets independently: %s"
          % (len(names), sys.argv[1]))


def main():
    require(len(sys.argv) == 2, "usage: verifier <schema-vectors.properties>")
    verify(properties(sys.argv[1]))


if __name__ == "__main__":
    main()
