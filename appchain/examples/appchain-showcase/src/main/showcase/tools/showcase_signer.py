#!/usr/bin/env python3
"""Demo-only RFC 8032 Ed25519 signer for packaged showcase actors.

The packaged CLI never reads direct-role private keys (ADR-025.2 §10.3); a
real deployment signs preimages in a wallet, KMS, or HSM. This tool plays that
external-signer role for the deterministic demo actors only. Python 3 standard
library only. Never reuse outside the packaged showcase.
"""
import hashlib
import sys

P = 2 ** 255 - 19
L = 2 ** 252 + 27742317777372353535851937790883648493
D = (-121665 * pow(121666, P - 2, P)) % P
BASE_Y = (4 * pow(5, P - 2, P)) % P


def recover_x(y, sign):
    numerator = (y * y - 1) % P
    denominator = (D * y * y + 1) % P
    candidate = pow(numerator * pow(denominator, P - 2, P), (P + 3) // 8, P)
    if (candidate * candidate - numerator * pow(denominator, P - 2, P)) % P:
        candidate = (candidate * pow(2, (P - 1) // 4, P)) % P
    if candidate % 2 != sign:
        candidate = P - candidate
    return candidate


BASE = (recover_x(BASE_Y, 0), BASE_Y)


def add(left, right):
    x1, y1 = left
    x2, y2 = right
    shared = (D * x1 * x2 * y1 * y2) % P
    x3 = ((x1 * y2 + x2 * y1) * pow(1 + shared, P - 2, P)) % P
    y3 = ((y1 * y2 + x1 * x2) * pow(1 - shared, P - 2, P)) % P
    return x3, y3


def multiply(point, scalar):
    result = (0, 1)
    while scalar:
        if scalar & 1:
            result = add(result, point)
        point = add(point, point)
        scalar >>= 1
    return result


def encode_point(point):
    x, y = point
    return (y | ((x & 1) << 255)).to_bytes(32, "little")


def secret_scalar(seed):
    if len(seed) != 32:
        raise ValueError("seed must be exactly 32 bytes")
    digest = hashlib.sha512(seed).digest()
    scalar = int.from_bytes(digest[:32], "little")
    scalar &= (1 << 254) - 8
    scalar |= 1 << 254
    return scalar, digest[32:]


def public_key(seed):
    scalar, _ = secret_scalar(seed)
    return encode_point(multiply(BASE, scalar))


def sign(seed, message):
    scalar, prefix = secret_scalar(seed)
    encoded_public = encode_point(multiply(BASE, scalar))
    r = int.from_bytes(hashlib.sha512(prefix + message).digest(), "little") % L
    encoded_r = encode_point(multiply(BASE, r))
    challenge = int.from_bytes(hashlib.sha512(
        encoded_r + encoded_public + message).digest(), "little") % L
    s = (r + challenge * scalar) % L
    return encoded_r + s.to_bytes(32, "little")


def main():
    if len(sys.argv) < 3:
        raise ValueError("usage: showcase_signer.py public-key <seed-hex> | "
                         "sign <seed-hex> <message-hex>")
    command, seed_hex = sys.argv[1], sys.argv[2]
    seed = bytes.fromhex(seed_hex)
    if command == "public-key":
        print(public_key(seed).hex())
    elif command == "sign":
        if len(sys.argv) != 4:
            raise ValueError("sign requires <seed-hex> <message-hex>")
        print(sign(seed, bytes.fromhex(sys.argv[3])).hex())
    else:
        raise ValueError(f"unknown command: {command}")


if __name__ == "__main__":
    try:
        main()
    except ValueError as failure:
        print(f"error: {failure}", file=sys.stderr)
        sys.exit(2)
