#!/usr/bin/env python3
"""Independent ADR-025 v1 codec, fingerprint, root, and proof-vector verifier."""

import hashlib
import pathlib
import struct
import sys


NULL_HASH = bytes(32)
VALUE_DOMAIN = b"yano-authenticated-map-value-v1\0"
BATCH_DOMAIN = b"yano-authenticated-map-batch-v1\0"
GENESIS_DOMAIN = b"yano-appchain-genesis-v1\0"
RESULT_DOMAIN = b"yano-authenticated-map-result-v1\0"
PROFILE_DOMAIN = b"yano-state-commitment-format-v1\0"


class VectorError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise VectorError(message)


def digest(data):
    return hashlib.blake2b(data, digest_size=32).digest()


def properties(path):
    result = {}
    with path.open("r", encoding="utf-8") as source:
        for number, line in enumerate(source, 1):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            require("=" in stripped, "malformed property at line %d" % number)
            key, value = stripped.split("=", 1)
            require(key and key not in result, "duplicate/blank property: " + key)
            result[key] = value
    return result


def read_length(data, offset, additional):
    if additional < 24:
        return additional, offset
    widths = {24: 1, 25: 2, 26: 4, 27: 8}
    require(additional in widths, "indefinite/reserved CBOR length")
    width = widths[additional]
    require(offset + width <= len(data), "truncated CBOR length")
    value = int.from_bytes(data[offset:offset + width], "big")
    minimum = {1: 24, 2: 256, 4: 65536, 8: 4294967296}[width]
    require(value >= minimum, "non-preferred CBOR integer/length")
    return value, offset + width


def read_cbor(data, offset=0):
    require(offset < len(data), "truncated CBOR item")
    initial = data[offset]
    offset += 1
    major = initial >> 5
    length, offset = read_length(data, offset, initial & 0x1F)
    if major == 0:
        return length, offset
    if major in (2, 3):
        require(offset + length <= len(data), "truncated CBOR string")
        raw = data[offset:offset + length]
        if major == 2:
            return raw, offset + length
        try:
            return raw.decode("utf-8"), offset + length
        except UnicodeDecodeError as error:
            raise VectorError("invalid UTF-8 CBOR text") from error
    if major == 4:
        items = []
        for _ in range(length):
            item, offset = read_cbor(data, offset)
            items.append(item)
        return items, offset
    raise VectorError("unsupported CBOR major type: %d" % major)


def cbor_head(major, value):
    require(value >= 0, "negative CBOR value")
    if value < 24:
        return bytes([(major << 5) | value])
    if value <= 0xFF:
        return bytes([(major << 5) | 24, value])
    if value <= 0xFFFF:
        return bytes([(major << 5) | 25]) + value.to_bytes(2, "big")
    if value <= 0xFFFFFFFF:
        return bytes([(major << 5) | 26]) + value.to_bytes(4, "big")
    return bytes([(major << 5) | 27]) + value.to_bytes(8, "big")


def encode_cbor(value):
    if isinstance(value, int):
        return cbor_head(0, value)
    if isinstance(value, bytes):
        return cbor_head(2, len(value)) + value
    if isinstance(value, str):
        encoded = value.encode("utf-8")
        return cbor_head(3, len(encoded)) + encoded
    if isinstance(value, list):
        return cbor_head(4, len(value)) + b"".join(encode_cbor(item) for item in value)
    raise VectorError("unsupported Python value for CBOR encoding")


def decode_exact(data):
    item, offset = read_cbor(data)
    require(offset == len(data), "trailing CBOR data")
    require(encode_cbor(item) == data, "CBOR is not preferred/canonical")
    return item


def nibbles(value):
    result = []
    for byte in value:
        result.extend((byte >> 4, byte & 0x0F))
    return result


def commit_leaf(key_hash, value_hash):
    require(len(key_hash) == 32 and len(value_hash) == 32, "invalid leaf hash length")
    return digest(b"\x00" + key_hash + value_hash)


def commit_branch(children):
    require(len(children) == 16, "branch must have 16 slots")
    bitmap = 0
    preimage = bytearray(b"\x01\x00\x00")
    for index, child in enumerate(children):
        if child is not None:
            require(len(child) == 32, "invalid child hash length")
            bitmap |= 1 << index
            preimage.extend(child)
        else:
            preimage.extend(NULL_HASH)
    preimage[1] = (bitmap >> 8) & 0xFF
    preimage[2] = bitmap & 0xFF
    return digest(bytes(preimage))


def build_root(leaves, depth=0):
    require(leaves, "cannot build an empty subtree")
    if len(leaves) == 1:
        key_hash, value_hash = next(iter(leaves.items()))
        return commit_leaf(key_hash, value_hash)
    require(depth < 64, "distinct keys did not diverge")
    groups = {}
    for key_hash, value_hash in leaves.items():
        groups.setdefault(nibbles(key_hash)[depth], {})[key_hash] = value_hash
    children = [None] * 16
    for nibble, group in groups.items():
        children[nibble] = build_root(group, depth + 1)
    return commit_branch(children)


def decode_node(encoded):
    item = decode_exact(encoded)
    require(isinstance(item, list) and item, "node must be a non-empty array")
    require(item[0] in (b"\x00", b"\x01", b"\x02"), "unknown JMT node tag")
    if item[0] == b"\x01":
        require(len(item) == 3, "leaf field count")
        require(all(isinstance(value, bytes) and len(value) == 32 for value in item[1:]),
                "leaf hash length")
        return "leaf", item[1], item[2]
    if item[0] == b"\x00":
        require(len(item) >= 2 and isinstance(item[1], int) and item[1] <= 0xFFFF,
                "internal bitmap")
        child_count = bin(item[1]).count("1")
        require(len(item) in (2 + child_count, 3 + child_count), "internal fields")
        children = item[2:2 + child_count]
        require(all(isinstance(value, bytes) and len(value) == 32 for value in children),
                "internal child hash length")
        return "internal", item[1], children
    raise VectorError("extension nodes are outside the v1 vector corpus")


def verify_jmt_wire(root, key, value, including, wire):
    try:
        encoded_nodes = decode_exact(wire)
        require(isinstance(encoded_nodes, list) and len(encoded_nodes) <= 65,
                "proof node count")
        nodes = [decode_node(encoded) for encoded in encoded_nodes]
        key_hash = digest(key)
        path = nibbles(key_hash)
        depths = [-1] * len(nodes)
        depth = 0
        terminal_missing = False
        terminal_leaf = None
        for index, node in enumerate(nodes):
            if node[0] == "internal":
                require(depth < len(path), "proof exceeds key depth")
                depths[index] = depth
                if not (node[1] & (1 << path[depth])):
                    require(index == len(nodes) - 1, "nodes after missing branch")
                    terminal_missing = True
                    break
                depth += 1
            elif node[0] == "leaf":
                require(index == len(nodes) - 1, "nodes after leaf")
                terminal_leaf = node
                break
        if including:
            require(value is not None and terminal_leaf is not None, "inclusion terminal")
            require(terminal_leaf[1] == key_hash, "leaf key mismatch")
            value_hash = digest(value)
            require(terminal_leaf[2] == value_hash, "leaf value mismatch")
            computed = commit_leaf(key_hash, value_hash)
        elif terminal_leaf is not None:
            require(terminal_leaf[1] != key_hash, "matching leaf disproves absence")
            require(nibbles(terminal_leaf[1])[:depth] == path[:depth],
                    "conflicting leaf path")
            computed = commit_leaf(terminal_leaf[1], terminal_leaf[2])
        else:
            require(terminal_missing or not nodes, "missing proof terminal")
            computed = None
        for index in range(len(nodes) - 1, -1, -1):
            node = nodes[index]
            if node[0] != "internal":
                continue
            bitmap = node[1]
            compact = iter(node[2])
            children = [next(compact) if bitmap & (1 << slot) else None
                        for slot in range(16)]
            children[path[depths[index]]] = computed
            computed = commit_branch(children)
        return (computed if computed is not None else NULL_HASH) == root
    except (VectorError, ValueError, IndexError, StopIteration):
        return False


def put_text(value):
    encoded = value.encode("ascii")
    return len(encoded).to_bytes(2, "big") + encoded


def profile_fingerprint(profile_id, backend, descriptor, proof, flags):
    canonical = (struct.pack(">I", 1) + put_text(profile_id) + bytes([backend])
                 + put_text(descriptor) + put_text(proof) + struct.pack(">I", 32)
                 + bytes([flags]))
    return digest(PROFILE_DOMAIN + canonical)


def decode_key(encoded, namespace=1):
    require(len(encoded) >= 9 and encoded[0:2] == bytes([1, namespace]), "key prefix")
    collection_length = int.from_bytes(encoded[2:4], "big")
    collection_end = 4 + collection_length
    require(0 < collection_length <= 64 and collection_end + 4 <= len(encoded),
            "collection length")
    collection = encoded[4:collection_end].decode("ascii")
    require(collection == collection.lower(), "collection canonical form")
    key_length = int.from_bytes(encoded[collection_end:collection_end + 4], "big")
    key = encoded[collection_end + 4:]
    require(0 < key_length <= 128 and len(key) == key_length, "application key length")
    return collection, key


def verify_vectors(values):
    require(values["schema.version"] == "1", "schema version")
    require(values["genesis.codec.version"] == "3", "genesis codec version")
    require(values["value.encoding.opaque"] == "0", "opaque value encoding")
    require(values["value.encoding.canonical-cbor"] == "1",
            "canonical CBOR value encoding")
    require(values["dependency.ccl.version"] == "0.8.0-pre5-dev1", "CCL pin")
    require(values["profile.mpf.id"] == "mpf-blake2b256-v1", "MPF profile id")
    require(values["profile.jmt.id"] == "jmt-blake2b256-v1", "JMT profile id")
    require(values["profile.jmt.descriptor"] == "classic-radix16-blake2b256-v1",
            "JMT descriptor")

    expected_mpf = profile_fingerprint(values["profile.mpf.id"], 0,
                                       values["profile.mpf.descriptor"],
                                       "ccl-mpf-proof-wire-v1", 2)
    expected_jmt = profile_fingerprint(values["profile.jmt.id"], 1,
                                       values["profile.jmt.descriptor"],
                                       "ccl-classic-jmt-proof-cbor-v1", 1)
    require(expected_mpf.hex() == values["profile.mpf.fingerprint"],
            "MPF format fingerprint")
    require(expected_jmt.hex() == values["profile.jmt.fingerprint"],
            "JMT format fingerprint")

    key = bytes.fromhex(values["key.products.sku1"])
    missing = bytes.fromhex(values["key.products.missing"])
    require(decode_key(key) == ("products", b"sku-1"), "sku key encoding")
    require(decode_key(missing) == ("products", b"missing"), "missing key encoding")
    require(decode_key(bytes.fromhex(values["key.framework.genesis"]), 0)
            == ("yano-authenticated-map-internal-v1", b"genesis"),
            "framework genesis key encoding")
    receipt_key = decode_key(bytes.fromhex(values["key.framework.receipt"]), 0)
    require(receipt_key == ("yano-authenticated-map-receipts-v1", bytes([0x55]) * 32),
            "framework receipt key encoding")
    require(digest(VALUE_DOMAIN + struct.pack(">I", 2) + b"\x01\x02").hex()
            == values["value.0102.hash"], "logical value hash")

    for name in ("command.put", "command.batch", "entry.active", "entry.revoked",
                 "genesis.cbor", "query.point.current", "query.point.history",
                 "query.point.result", "query.receipt", "receipt.applied",
                 "receipt.rejected", "query.receipt.result",
                 "jmt.proof.inclusion", "jmt.proof.absence"):
        decode_exact(bytes.fromhex(values[name]))
    batch = bytes.fromhex(values["command.batch"])
    require(digest(BATCH_DOMAIN + batch).hex() == values["command.batch.commitment"],
            "batch commitment")
    genesis_bytes = bytes.fromhex(values["genesis.cbor"])
    genesis = decode_exact(genesis_bytes)
    require(genesis[0:4] == [3, "product-registry", "authenticated-map", 1]
            and len(genesis) == 14, "genesis codec/machine version")
    require(all(isinstance(collection, list) and len(collection) == 8
                and collection[0] == 3 and collection[6:] == [0, ""]
                for collection in genesis[11]), "collection value encoding")
    require(genesis[12] == [], "golden genesis validator catalog")
    require(digest(GENESIS_DOMAIN + genesis_bytes).hex() == values["genesis.id"],
            "genesis id")

    active = decode_exact(bytes.fromhex(values["entry.active"]))
    require(active[0:3] == [1, 0, 1] and len(active) == 8, "active entry shape")
    require(digest(VALUE_DOMAIN + struct.pack(">I", len(active[4])) + active[4]) == active[5],
            "active entry logical value hash")
    revoked = decode_exact(bytes.fromhex(values["entry.revoked"]))
    require(revoked[0:3] == [1, 1, 2] and revoked[4] == b"" and revoked[5] == active[5],
            "revoked tombstone shape")

    current_query = decode_exact(bytes.fromhex(values["query.point.current"]))
    history_query = decode_exact(bytes.fromhex(values["query.point.history"]))
    require(current_query == [1, 0, 0, "products", b"sku-1"],
            "current point query shape")
    require(history_query == [1, 1, 7, "products", b"sku-1"],
            "historical point query shape")
    point_result = decode_exact(bytes.fromhex(values["query.point.result"]))
    require(point_result[:6] == [1, 7, bytes([0x66]) * 32,
                                 "products", b"sku-1", 1],
            "point result shape")
    require(point_result[6] == bytes.fromhex(values["entry.active"]),
            "point result entry")

    receipt = decode_exact(bytes.fromhex(values["receipt.applied"]))
    require(receipt[:5] == [1, bytes([0x55]) * 32, 7, 0, 0]
            and len(receipt[7]) == 1, "applied receipt shape")
    result_material = [1, receipt[3], receipt[4], receipt[7]]
    require(digest(RESULT_DOMAIN + encode_cbor(result_material)) == receipt[6],
            "applied receipt result commitment")
    rejected_receipt = decode_exact(bytes.fromhex(values["receipt.rejected"]))
    require(rejected_receipt[:5] == [1, bytes([0x56]) * 32, 8, 1, 8]
            and rejected_receipt[7] == [], "rejected receipt shape")
    rejected_material = [1, rejected_receipt[3], rejected_receipt[4], []]
    require(digest(RESULT_DOMAIN + encode_cbor(rejected_material)) == rejected_receipt[6],
            "rejected receipt result commitment")
    receipt_result = decode_exact(bytes.fromhex(values["query.receipt.result"]))
    require(receipt_result[:5] == [1, 7, bytes([0x66]) * 32,
                                   bytes([0x55]) * 32, 1]
            and receipt_result[5] == bytes.fromhex(values["receipt.applied"]),
            "receipt query result shape")

    count = int(values["workload.count"])
    require(count == 3, "workload count")
    leaves = {}
    for index in range(count):
        update_key = bytes.fromhex(values["workload.%d.key" % index])
        update_value = bytes.fromhex(values["workload.%d.value" % index])
        decode_key(update_key)
        decode_exact(update_value)
        leaves[digest(update_key)] = digest(update_value)
    jmt_root = bytes.fromhex(values["jmt.root"])
    require(build_root(leaves) == jmt_root, "independent JMT root")
    require(verify_jmt_wire(jmt_root, key, bytes.fromhex(values["workload.0.value"]),
                            True, bytes.fromhex(values["jmt.proof.inclusion"])),
            "JMT inclusion proof")
    require(verify_jmt_wire(jmt_root, missing, None, False,
                            bytes.fromhex(values["jmt.proof.absence"])),
            "JMT non-inclusion proof")
    require(len(bytes.fromhex(values["mpf.root"])) == 32, "MPF root length")
    require(bytes.fromhex(values["mpf.proof.inclusion"])
            and bytes.fromhex(values["mpf.proof.absence"]), "MPF proof vectors")


def main():
    require(len(sys.argv) == 2, "usage: verify-authenticated-map-vectors.py FILE")
    path = pathlib.Path(sys.argv[1])
    verify_vectors(properties(path))
    print("Verified authenticated-map v1 vectors independently: %s" % path)


if __name__ == "__main__":
    try:
        main()
    except (VectorError, KeyError, TypeError, ValueError, OSError) as error:
        print("authenticated-map vector verification failed: %s" % error, file=sys.stderr)
        sys.exit(1)
