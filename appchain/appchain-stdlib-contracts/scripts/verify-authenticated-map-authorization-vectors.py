#!/usr/bin/env python3
"""Dependency-free verifier for ADR-025.2 authenticated-map v1 vectors."""

import hashlib
import pathlib
import sys

Q = 2**255 - 19
L = 2**252 + 27742317777372353535851937790883648493
D = (-121665 * pow(121666, Q - 2, Q)) % Q
I = pow(2, (Q - 1) // 4, Q)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def recover_x(y, sign):
    x2 = (y * y - 1) * pow(D * y * y + 1, Q - 2, Q) % Q
    x = pow(x2, (Q + 3) // 8, Q)
    if (x * x - x2) % Q:
        x = x * I % Q
    if (x * x - x2) % Q or x == 0 and sign:
        raise ValueError("invalid Ed25519 point")
    return Q - x if (x & 1) != sign else x


def decode_point(encoded):
    require(len(encoded) == 32, "invalid Ed25519 point length")
    value = int.from_bytes(encoded, "little")
    y = value & ((1 << 255) - 1)
    require(y < Q, "non-canonical Ed25519 point")
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


BASE_Y = 4 * pow(5, Q - 2, Q) % Q
BASE = (recover_x(BASE_Y, 0), BASE_Y)


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
    require(width is not None and offset + width <= len(data), "unsupported CBOR")
    value = int.from_bytes(data[offset:offset + width], "big")
    minima = {1: 24, 2: 256, 4: 65536, 8: 2**32}
    require(value >= minima[width],
            "non-preferred CBOR argument")
    return value, offset + width


def decode_cbor(data, offset=0, depth=0):
    require(depth <= 12 and offset < len(data), "invalid CBOR")
    initial = data[offset]
    major, additional = initial >> 5, initial & 31
    require(additional != 31, "indefinite CBOR is forbidden")
    value, offset = read_argument(data, offset + 1, additional)
    if major == 0:
        return value, offset
    if major in (2, 3):
        end = offset + value
        require(end <= len(data), "truncated CBOR")
        raw = data[offset:end]
        return (raw if major == 2 else raw.decode("utf-8")), end
    if major == 4:
        values = []
        for _ in range(value):
            item, offset = decode_cbor(data, offset, depth + 1)
            values.append(item)
        return values, offset
    raise ValueError("unsupported CBOR type")


def encode_head(major, value):
    if value < 24:
        return bytes([(major << 5) | value])
    for additional, width, maximum in ((24, 1, 255), (25, 2, 65535),
                                        (26, 4, 2**32 - 1), (27, 8, 2**64 - 1)):
        if value <= maximum:
            return bytes([(major << 5) | additional]) + value.to_bytes(width, "big")
    raise ValueError("CBOR integer too large")


def encode_cbor(value):
    if isinstance(value, int):
        return encode_head(0, value)
    if isinstance(value, bytes):
        return encode_head(2, len(value)) + value
    if isinstance(value, str):
        encoded = value.encode("utf-8")
        return encode_head(3, len(encoded)) + encoded
    if isinstance(value, list):
        return encode_head(4, len(value)) + b"".join(encode_cbor(item) for item in value)
    raise ValueError("unsupported CBOR value")


def exact_cbor(encoded):
    value, end = decode_cbor(encoded)
    require(end == len(encoded) and encode_cbor(value) == encoded,
            "value is not exact preferred CBOR")
    return value


def blake2b256(value):
    return hashlib.blake2b(value, digest_size=32).digest()


def load_properties(path):
    properties = {}
    for line in pathlib.Path(path).read_text(encoding="ascii").splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            properties[key] = value
    return properties


def decode_key(encoded):
    require(encoded[:2] == b"\x01\x00", "consumption key namespace")
    collection_length = int.from_bytes(encoded[2:4], "big")
    collection_end = 4 + collection_length
    collection = encoded[4:collection_end].decode("ascii")
    key_length = int.from_bytes(encoded[collection_end:collection_end + 4], "big")
    key = encoded[collection_end + 4:]
    require(len(key) == key_length, "consumption key length")
    return collection, key


def main(path):
    vectors = load_properties(path)
    require(vectors["schema.version"] == "1", "schema version")
    public_key = bytes.fromhex(vectors["public-key"])

    action_bytes = bytes.fromhex(vectors["action.cbor"])
    action = exact_cbor(action_bytes)
    require(action[0:2] == [1, 1] and len(action[2]) == 3 and len(action[3]) == 3,
            "mixed action shape")
    require(action[3] == [[0, 0, "", 0], [1, 3, "issuer-write", 1],
                          [2, 4, "release-policy", 2]], "action assignments")
    commitment = blake2b256(b"yano:authenticated-map:action:v1\0" + action_bytes)
    require(commitment.hex() == vectors["action.commitment"], "action commitment")

    statement = bytes.fromhex(vectors["actor.statement"])
    statement_value = exact_cbor(statement)
    require(statement_value[0] == 1 and statement_value[4] == commitment
            and statement_value[5] == [1] and statement_value[11] == public_key,
            "actor statement binding")
    actor_preimage = (b"yano:authenticated-map:actor-authorization:v1\0"
                      + len(statement).to_bytes(4, "big") + statement)
    require(actor_preimage.hex() == vectors["actor.preimage"], "actor preimage")
    actor_signature = bytes.fromhex(vectors["actor.signature"])
    require(verify(actor_signature, actor_preimage, public_key), "actor signature")
    require(exact_cbor(bytes.fromhex(vectors["actor.evidence"]))
            == [1, statement, actor_signature], "actor evidence")

    approval_bytes = bytes.fromhex(vectors["approval.reference"])
    approval = exact_cbor(approval_bytes)
    require(approval == [1, "release-001", commitment, [2], "release-policy", 1],
            "approval reference")
    command = exact_cbor(bytes.fromhex(vectors["command.cbor"]))
    require(command == [1, action_bytes, [[0, bytes.fromhex(vectors["actor.evidence"])],
                                          [1, approval_bytes]]], "command evidence table")

    genesis_bytes = bytes.fromhex(vectors["genesis.cbor"])
    genesis = exact_cbor(genesis_bytes)
    require(genesis[0:4] == [4, "authenticated-map-chain", "authenticated-map", 1]
            and genesis[14] == bytes.fromhex(vectors["genesis.governed-closure"]),
            "governed genesis")
    genesis_id = blake2b256(b"yano-appchain-genesis-v1\0" + genesis_bytes)
    require(genesis_id.hex() == vectors["genesis.id"], "genesis id")
    require(statement_value[3] == genesis_id, "actor genesis binding")

    proof = exact_cbor(bytes.fromhex(vectors["genesis.key-proof"]))
    proof_unsigned = encode_cbor(proof[:5])
    proof_preimage = (b"yano:actor-key-proof:v1\0"
                      + len(proof_unsigned).to_bytes(4, "big") + proof_unsigned)
    require(proof[4][1] == public_key and verify(proof[5], proof_preimage, public_key),
            "genesis proof of possession")

    administrator = bytes.fromhex(vectors["administrator.statement"])
    administrator_value = exact_cbor(administrator)
    administrator_preimage = (b"yano:authenticated-map:administrator:v1\0"
                              + len(administrator).to_bytes(4, "big") + administrator)
    administrator_signature = bytes.fromhex(vectors["administrator.signature"])
    require(administrator_preimage.hex() == vectors["administrator.preimage"]
            and administrator_value[3] == genesis_id
            and administrator_value[13] == public_key
            and verify(administrator_signature, administrator_preimage, public_key),
            "administrator statement")
    governance_command = exact_cbor(bytes.fromhex(vectors["administrator.command"]))
    mutation = governance_command[3]
    mutation_hash = blake2b256(
        b"yano:authenticated-map:governed-mutation:v1\0"
        + len(mutation).to_bytes(4, "big") + mutation)
    require(governance_command[0:3] == [1, 0, "mutation-001"]
            and administrator_value[7] == mutation_hash,
            "governance mutation commitment")

    direct_a = decode_key(bytes.fromhex(vectors["consumption.direct.key.actor-a"]))
    direct_b = decode_key(bytes.fromhex(vectors["consumption.direct.key.actor-b"]))
    require(direct_a[0] == direct_b[0]
            == "yano-authenticated-map-direct-consumption-v1"
            and direct_a[1] != direct_b[1]
            and direct_a[1][-32:] == direct_b[1][-32:], "actor-namespaced direct replay key")
    approval_key = decode_key(bytes.fromhex(vectors["consumption.approval.key"]))
    require(approval_key == ("yano-authenticated-map-approval-consumption-v1",
                             b"release-001"), "single proposal replay key")

    for name in ("authority", "limits", "policy.direct", "policy.approval",
                 "genesis.governed-closure", "consumption.direct.value",
                 "consumption.approval.value"):
        exact_cbor(bytes.fromhex(vectors[name]))
    print("PASS authenticated-map-authorization-v1 independent vectors")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(
            "usage: verify-authenticated-map-authorization-vectors.py <vectors.properties>")
    main(sys.argv[1])
