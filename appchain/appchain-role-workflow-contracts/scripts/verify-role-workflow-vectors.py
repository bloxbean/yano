#!/usr/bin/env python3
"""Dependency-free, independent verifier for ADR-019 v1 golden vectors."""

import hashlib
import pathlib
import sys

Q = 2**255 - 19
L = 2**252 + 27742317777372353535851937790883648493
D = (-121665 * pow(121666, Q - 2, Q)) % Q
I = pow(2, (Q - 1) // 4, Q)


def recover_x(y, sign):
    x2 = (y * y - 1) * pow(D * y * y + 1, Q - 2, Q) % Q
    x = pow(x2, (Q + 3) // 8, Q)
    if (x * x - x2) % Q:
        x = x * I % Q
    if (x * x - x2) % Q or x == 0 and sign:
        raise ValueError("invalid Ed25519 point")
    return Q - x if (x & 1) != sign else x


def decode_point(encoded):
    if len(encoded) != 32:
        raise ValueError("invalid Ed25519 point length")
    value = int.from_bytes(encoded, "little")
    y = value & ((1 << 255) - 1)
    if y >= Q:
        raise ValueError("non-canonical Ed25519 point")
    return recover_x(y, value >> 255), y


def add(left, right):
    x1, y1 = left
    x2, y2 = right
    product = D * x1 * x2 * y1 * y2 % Q
    x3 = (x1 * y2 + x2 * y1) * pow(1 + product, Q - 2, Q) % Q
    y3 = (y1 * y2 + x1 * x2) * pow(1 - product, Q - 2, Q) % Q
    return x3, y3


def multiply(point, scalar):
    result = (0, 1)
    addend = point
    while scalar:
        if scalar & 1:
            result = add(result, addend)
        addend = add(addend, addend)
        scalar >>= 1
    return result


BASE = (recover_x(4 * pow(5, Q - 2, Q) % Q, 0), 4 * pow(5, Q - 2, Q) % Q)


def verify(signature, message, public_key):
    if len(signature) != 64:
        return False
    r_bytes, s_bytes = signature[:32], signature[32:]
    scalar = int.from_bytes(s_bytes, "little")
    if scalar >= L:
        return False
    try:
        public = decode_point(public_key)
        r_point = decode_point(r_bytes)
    except ValueError:
        return False
    challenge = int.from_bytes(
        hashlib.sha512(r_bytes + public_key + message).digest(), "little") % L
    return multiply(BASE, scalar) == add(r_point, multiply(public, challenge))


def read_argument(data, offset, additional):
    if additional < 24:
        return additional, offset
    widths = {24: 1, 25: 2, 26: 4, 27: 8}
    width = widths.get(additional)
    if width is None or offset + width > len(data):
        raise ValueError("unsupported CBOR argument")
    return int.from_bytes(data[offset:offset + width], "big"), offset + width


def decode_cbor(data, offset=0, depth=0):
    if depth > 8 or offset >= len(data):
        raise ValueError("invalid CBOR")
    initial = data[offset]
    major, additional = initial >> 5, initial & 31
    value, offset = read_argument(data, offset + 1, additional)
    if major == 0:
        return value, offset
    if major in (2, 3):
        end = offset + value
        if end > len(data):
            raise ValueError("truncated CBOR")
        raw = data[offset:end]
        return (raw if major == 2 else raw.decode("ascii")), end
    if major == 4:
        values = []
        for _ in range(value):
            item, offset = decode_cbor(data, offset, depth + 1)
            values.append(item)
        return values, offset
    raise ValueError("unsupported CBOR type")


def encode_cbor(value):
    if isinstance(value, int):
        major, payload = 0, value
    elif isinstance(value, bytes):
        major, payload = 2, len(value)
    elif isinstance(value, str):
        encoded = value.encode("ascii")
        return encode_head(3, len(encoded)) + encoded
    elif isinstance(value, list):
        return encode_head(4, len(value)) + b"".join(encode_cbor(item) for item in value)
    else:
        raise ValueError("unsupported value")
    head = encode_head(major, payload)
    return head + value if isinstance(value, bytes) else head


def encode_head(major, value):
    if value < 24:
        return bytes([(major << 5) | value])
    for additional, width, maximum in ((24, 1, 255), (25, 2, 65535),
                                        (26, 4, 2**32 - 1), (27, 8, 2**64 - 1)):
        if value <= maximum:
            return bytes([(major << 5) | additional]) + value.to_bytes(width, "big")
    raise ValueError("CBOR integer too large")


def load_properties(path):
    properties = {}
    for line in pathlib.Path(path).read_text(encoding="ascii").splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            properties[key] = value
    return properties


def main(path):
    vectors = load_properties(path)
    statement = bytes.fromhex(vectors["statement.propose"])
    decoded, end = decode_cbor(statement)
    if end != len(statement) or encode_cbor(decoded) != statement:
        raise ValueError("statement is not preferred CBOR")
    expected = [1, 0, "demo-chain", "evidence-001", "evidence-release", 1,
                "evidence.release.v1", bytes([0x11]) * 32, 100,
                "manufacturer-a", 1, "signing-1", ""]
    if decoded != expected:
        raise ValueError("statement intent mismatch")
    preimage = b"yano:role-approval:v1\0" + len(statement).to_bytes(4, "big") + statement
    if preimage.hex() != vectors["statement.preimage"]:
        raise ValueError("statement preimage mismatch")
    public_key = bytes.fromhex(vectors["public-key"])
    signature = bytes.fromhex(vectors["statement.signature"])
    if not verify(signature, preimage, public_key):
        raise ValueError("statement signature failed independent verification")
    command = bytes.fromhex(vectors["command.propose"])
    command_value, end = decode_cbor(command)
    if end != len(command) or command_value != [1, statement, signature]:
        raise ValueError("signed command mismatch")
    proof = bytes.fromhex(vectors["key-proof"])
    proof_value, end = decode_cbor(proof)
    if end != len(proof) or len(proof_value) != 6:
        raise ValueError("key proof mismatch")
    proof_unsigned = encode_cbor(proof_value[:5])
    proof_preimage = (b"yano:actor-key-proof:v1\0"
                      + len(proof_unsigned).to_bytes(4, "big") + proof_unsigned)
    if proof_value[4][1] != public_key or not verify(
            proof_value[5], proof_preimage, public_key):
        raise ValueError("key proof signature failed independent verification")
    print("PASS role-workflow-v1 independent vectors")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify-role-workflow-vectors.py <golden-vectors.properties>")
    main(sys.argv[1])
