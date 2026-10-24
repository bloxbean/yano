#!/usr/bin/env python3
"""Independent test vectors for the topic-registry v1 formats (ADR app-layer/039, V2).

Built from META-INF/yano/topics/v1/topics.cddl with a separate CBOR library (cbor2, canonical mode)
and a separate Ed25519 implementation (PyNaCl / libsodium), never from the Java code under test.

    python3 -m venv .venv && .venv/bin/pip install cbor2 pynacl
    .venv/bin/python scripts/topics/generate_topic_vectors.py \
        > core-api/src/test/resources/topics/v1/vectors.json
"""
import hashlib
import json

import cbor2
import nacl.signing

CHAIN = "topics-vector-chain"
GENERATION = bytes(range(32))


def enc(value):
    return cbor2.dumps(value, canonical=True)


def h(data):
    return hashlib.blake2b(data, digest_size=32).digest()


def signer(seed_byte):
    key = nacl.signing.SigningKey(bytes([seed_byte]) * 32)
    return key, key.verify_key.encode()


def sign(key, message):
    return key.sign(message).signature


def key_set(threshold, keys):
    return [threshold, sorted(keys)]


OWNER_A, OWNER_A_PK = signer(1)
OWNER_B, OWNER_B_PK = signer(2)
ISSUER, ISSUER_PK = signer(3)
SESSION, SESSION_PK = signer(4)
WRITER, WRITER_PK = signer(5)

vectors = []


def add(name, kind, value, **derived):
    vectors.append({"name": name, "type": kind, "hex": enc(value).hex(), **derived})


def add_invalid(name, kind, raw_bytes, reason):
    vectors.append({"name": name, "type": kind, "hex": raw_bytes.hex(), "invalid": reason})


# --- policies and configuration ----------------------------------------------------------------
settings = [True, None, False, True, 3_600_000, 604_800_000, 4, 100_000, 1_000, 10, 600]
add("config", "config", [1, 1, 1, settings])
registrar_settings = [True, key_set(1, [OWNER_A_PK, OWNER_B_PK]), True, False, 60_000, 86_400_000, 2,
                      500, 50, 1, 20]
add("config-registrars", "config", [1, 7, 4, registrar_settings])

open_policy = [0, None, None, b"", None]
writer_policy = [key_set(1, [ISSUER_PK, WRITER_PK]), sorted([ISSUER_PK]), 3_600_000, h(b"schema-ir"), 4096]
inherit_policy = [None, None, None, None, None]

# --- registry state ------------------------------------------------------------------------------
content = [key_set(2, [OWNER_A_PK, OWNER_B_PK]), writer_policy, False]
add("entry-active", "entry-record", [1, "acme/", [3, 120, 2, content], 4, 1, 17])
add("entry-pending-creation", "entry-record", [1, "acme/orders", None, 1, 1, 0])
add("queued-revision", "queued-revision", [4, 131, "acme/", 3, content, h(b"command-message")])
add("entry-open-retired", "entry-record",
    [1, "acme/old", [2, 77, 1, [key_set(1, [OWNER_A_PK]), open_policy, True]], 2, 0, 0])
add("entry-inherit", "entry-record", [1, "acme/new", [1, 90, 1, [key_set(1, [WRITER_PK]), inherit_policy, False]],
                                      1, 0, 0])
add("expiry-batch", "expiry-batch", [1_800_000_360_000, 42, 3, 1])
add("expiry-batch-last", "expiry-batch", [1_800_000_360_000, 0, 1, 0])
add("expiry-item-used", "expiry-item", [0, h(b"used")])
add("expiry-item-receipt", "expiry-item", [2, h(b"message-id")])
add("activation-entry", "activation", ["acme/orders/", 3, h(b"create-command")])
add("activation-config", "activation", ["~config", 2, h(b"configure-command")])
add("receipt-accepted", "receipt", ["ACCEPTED", 131])
add("receipt-stale", "receipt", ["TOPIC_COMMAND_STALE", 0])
add("governance-pending", "governance-pending",
    [[h(b"command"), 9, sorted([OWNER_A_PK, OWNER_B_PK])]])

# --- delegation ----------------------------------------------------------------------------------
delegation_fields = [1, SESSION_PK, "acme/orders/", 1_800_000_000_000, 1_800_003_600_000, ISSUER_PK]
delegation_signed = enc(["yano-topic-delegation-v1", CHAIN, GENERATION] + delegation_fields)
delegation_sig = sign(ISSUER, delegation_signed)
delegation = delegation_fields + [delegation_sig]
delegation_hash = h(delegation_signed)
add("delegation", "delegation", delegation,
    chainId=CHAIN, generationId=GENERATION.hex(),
    signedBytes=delegation_signed.hex(), delegationHash=delegation_hash.hex())

# --- registry commands ---------------------------------------------------------------------------
nonce = bytes(range(16, 32))


def registry_command(name, statement, keys):
    statement_bytes = enc(statement)
    signatures = sorted([[pk, sign(sk, statement_bytes)] for sk, pk in keys], key=lambda s: s[0])
    add(name, "registry-command", [1, statement, signatures],
        statementHex=statement_bytes.hex())


registry_command("command-create-child",
                 ["yano-topic-command-v1", CHAIN, GENERATION, "acme/orders/", "acme/", None, nonce,
                  1_800_000_600_000, [0, key_set(1, [WRITER_PK]), inherit_policy, None]],
                 [(OWNER_A, OWNER_A_PK), (OWNER_B, OWNER_B_PK)])
registry_command("command-update-with-schema",
                 ["yano-topic-command-v1", CHAIN, GENERATION, "acme/orders/", "acme/orders/", 2, nonce,
                  1_800_000_600_000, [1, writer_policy, b"schema-ir"]],
                 [(WRITER, WRITER_PK)])
registry_command("command-rotate",
                 ["yano-topic-command-v1", CHAIN, GENERATION, "acme/", "acme/", 4, nonce, 1_800_000_600_000,
                  [2, key_set(1, [ISSUER_PK])]],
                 [(OWNER_A, OWNER_A_PK), (OWNER_B, OWNER_B_PK)])
registry_command("command-retire",
                 ["yano-topic-command-v1", CHAIN, GENERATION, "acme/old", "acme/", 1, nonce, 1_800_000_600_000,
                  [3]],
                 [(OWNER_A, OWNER_A_PK)])
registry_command("command-revoke",
                 ["yano-topic-command-v1", CHAIN, GENERATION, "acme/orders/", "~issuer", None, nonce,
                  1_800_000_600_000, [4, delegation]],
                 [(ISSUER, ISSUER_PK)])
registry_command("command-open-claim",
                 ["yano-topic-command-v1", CHAIN, GENERATION, "neon/", "~claim", None, nonce, 1_800_000_600_000,
                  [0, key_set(1, [OWNER_B_PK]), open_policy, None]],
                 [(OWNER_B, OWNER_B_PK)])

# --- governance commands -------------------------------------------------------------------------
add("governance-create", "governance-command",
    ["yano-topic-governance-v1", CHAIN, GENERATION, nonce,
     [0, "acme/", key_set(2, [OWNER_A_PK, OWNER_B_PK]), inherit_policy, None]])
add("governance-recover", "governance-command",
    ["yano-topic-governance-v1", CHAIN, GENERATION, nonce, [1, "acme/", key_set(1, [ISSUER_PK])]])
add("governance-retire", "governance-command",
    ["yano-topic-governance-v1", CHAIN, GENERATION, nonce, [2, "spam/"]])
add("governance-configure", "governance-command",
    ["yano-topic-governance-v1", CHAIN, GENERATION, nonce, [3, registrar_settings]])

# --- writes --------------------------------------------------------------------------------------
payload = b"\x82\x01\x02"  # canonical CBOR [1, 2]
payload_hash = h(payload)
write_nonce = bytes(range(32, 48))
delegated_statement = ["yano-topic-write-v1", CHAIN, GENERATION, "acme/orders/new", payload_hash, write_nonce,
                       1_800_000_060_000, delegation_hash]
delegated_bytes = enc(delegated_statement)
add("frame-delegated", "frame",
    [1, payload, [write_nonce, 1_800_000_060_000, [[SESSION_PK, sign(SESSION, delegated_bytes)]], delegation]],
    topic="acme/orders/new", chainId=CHAIN, generationId=GENERATION.hex(),
    statementHex=delegated_bytes.hex(), statementHash=h(delegated_bytes).hex(), payloadHash=payload_hash.hex())

direct_statement = ["yano-topic-write-v1", CHAIN, GENERATION, "acme/orders/new", payload_hash, write_nonce,
                    1_800_000_060_000, None]
direct_bytes = enc(direct_statement)
direct_signatures = sorted([[pk, sign(sk, direct_bytes)] for sk, pk in [(WRITER, WRITER_PK), (ISSUER, ISSUER_PK)]],
                           key=lambda s: s[0])
add("frame-direct-2-of-2", "frame", [1, payload, [write_nonce, 1_800_000_060_000, direct_signatures, None]],
    topic="acme/orders/new", chainId=CHAIN, generationId=GENERATION.hex(),
    statementHex=direct_bytes.hex(), statementHash=h(direct_bytes).hex(), payloadHash=payload_hash.hex())

# --- effective outcomes --------------------------------------------------------------------------
resolved = [key_set(1, [ISSUER_PK, WRITER_PK]), sorted([ISSUER_PK]), 3_600_000, h(b"schema-ir"), 4096, False]
for name, outcome in [
    ("outcome-policy", ["yano-topic-outcome-v1", 1, resolved, 3, [["acme/", 3], ["acme/orders/", 2]]]),
    ("outcome-disabled", ["yano-topic-outcome-v1", 0, None, None, []]),
    ("outcome-unregistered-open", ["yano-topic-outcome-v1", 2, None, 1, []]),
    ("outcome-unregistered-reject", ["yano-topic-outcome-v1", 3, None, 1, []]),
]:
    add(name, "effective-outcome", outcome, digest=h(enc(outcome)).hex())

# --- invalid encodings ---------------------------------------------------------------------------
config_bytes = enc([1, 1, 1, settings])
add_invalid("non-shortest-integer", "config",
            bytes([0x83, 0x18, 0x01]) + config_bytes[2:], "version 1 encoded in two bytes")
add_invalid("indefinite-array", "config", bytes([0x9f]) + config_bytes[1:] + bytes([0xff]), "indefinite length")
add_invalid("tagged-item", "config", bytes([0x83, 0xc1, 0x01]) + config_bytes[2:], "tag on the version")
add_invalid("map-instead-of-array", "config", enc({0: 1}), "maps are not allowed")
add_invalid("unsorted-key-set", "registry-command",
            enc([1, ["yano-topic-command-v1", CHAIN, GENERATION, "acme/", "acme/", 1, nonce, 1,
                     [2, [1, sorted([OWNER_A_PK, OWNER_B_PK], reverse=True)]]],
                 [[OWNER_A_PK, bytes(64)]]]), "key-set keys out of order")
add_invalid("duplicate-signature-key", "registry-command",
            enc([1, ["yano-topic-command-v1", CHAIN, GENERATION, "acme/", "acme/", 1, nonce, 1, [3]],
                 [[OWNER_A_PK, bytes(64)], [OWNER_A_PK, bytes(64)]]]), "a key signs twice")
add_invalid("trailing-bytes", "delegation", enc(delegation) + b"\x00", "trailing byte")
receipt_ok = enc(["ACCEPTED", 1])
add_invalid("invalid-utf8", "receipt", bytes([0x82, 0x62, 0xc3, 0x28, 0x01]), "invalid UTF-8 text")
add_invalid("negative-integer", "receipt", bytes([0x82, 0x60, 0x20]), "negative integer")
add_invalid("float", "receipt", bytes([0x82, 0x60, 0xf9, 0x3c, 0x00]), "half-precision float")
add_invalid("integer-2-pow-63", "receipt", bytes([0x82, 0x60, 0x1b, 0x80, 0, 0, 0, 0, 0, 0, 0]),
            "integer beyond 2^63 - 1")
add_invalid("bignum-tag", "receipt", bytes([0x82, 0x60, 0xc2, 0x41, 0x01]), "bignum tag")
add_invalid("two-byte-simple", "receipt", bytes([0x82, 0x60, 0xf8, 0x20]), "two-byte simple value")
add_invalid("non-shortest-length", "receipt", bytes([0x82, 0x78, 0x00, 0x01]), "text length in two bytes")
add_invalid("indefinite-text", "receipt", bytes([0x82, 0x7f, 0x60, 0xff, 0x01]), "indefinite-length text")
add_invalid("indefinite-signatures", "registry-command",
            enc([1, ["yano-topic-command-v1", CHAIN, GENERATION, "acme/", "acme/", 1, nonce, 1, [3]], []])[:-1]
            + bytes([0x9f]) + enc([OWNER_A_PK, bytes(64)]) + bytes([0xff]), "indefinite-length signature list")
add_invalid("unsorted-signatures", "registry-command",
            enc([1, ["yano-topic-command-v1", CHAIN, GENERATION, "acme/", "acme/", 1, nonce, 1, [3]],
                 sorted([[OWNER_A_PK, bytes(64)], [OWNER_B_PK, bytes(64)]], key=lambda s: s[0], reverse=True)]),
            "signatures out of key order")
add_invalid("empty-signatures", "registry-command",
            enc([1, ["yano-topic-command-v1", CHAIN, GENERATION, "acme/", "acme/", 1, nonce, 1, [3]], []]),
            "no signatures")
add_invalid("wrong-domain", "registry-command",
            enc([1, ["yano-topic-write-v1", CHAIN, GENERATION, "acme/", "acme/", 1, nonce, 1, [3]],
                 [[OWNER_A_PK, bytes(64)]]]), "write domain in a command")
add_invalid("unknown-operation", "registry-command",
            enc([1, ["yano-topic-command-v1", CHAIN, GENERATION, "acme/", "acme/", 1, nonce, 1, [9]],
                 [[OWNER_A_PK, bytes(64)]]]), "operation 9")
add_invalid("expected-zero", "registry-command",
            enc([1, ["yano-topic-command-v1", CHAIN, GENERATION, "acme/", "acme/", 0, nonce, 1, [3]],
                 [[OWNER_A_PK, bytes(64)]]]), "expected head revision 0")
add_invalid("oversized-entry-record", "entry-record",
            enc([1, "a" * 4_096, None, 1, 1, 0]), "entry record above 4,096 bytes")
add_invalid("pruned-equals-count", "expiry-batch", enc([1, 0, 1, 1]), "pruned must be below count")

print(json.dumps({"chainId": CHAIN, "generationId": GENERATION.hex(), "vectors": vectors}, indent=2))
