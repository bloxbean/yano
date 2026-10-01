#!/usr/bin/env python3
"""Replay dumped scenario requests through the built .wasm and compare with the native responses.

    AMARU_DUMP_REQUESTS_DIR=target/scenario-requests cargo test --locked --test amaru_scenarios
    python3 scripts/run_wasm_scenarios.py target/wasm-out/amaru_validator.wasm target/scenario-requests

Every `<label>.req.cbor` is passed to the module's `validate` export and the response must equal
`<label>.resp.cbor` (produced by the same code compiled natively) byte for byte. Likewise every
`<label>.keys-tx.cbor` / `<label>.keys-env.cbor` pair goes through `required_keys` and must give
`<label>.keys-resp.cbor`. The WASI host is
minimal, as in ADR-057 invariant 2: no preopened directories, no arguments, no environment.

Needs the `wasmtime` Python package. This is a build check of the artifact, not the Java engine
path (Endive), which is ADR-057 Phase B.
"""
import pathlib
import statistics
import sys
import time

import wasmtime


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    wasm_path, requests_dir = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])

    engine = wasmtime.Engine()
    module = wasmtime.Module.from_file(engine, str(wasm_path))
    linker = wasmtime.Linker(engine)
    linker.define_wasi()
    store = wasmtime.Store(engine)
    store.set_wasi(wasmtime.WasiConfig())  # nothing preopened, no args, no environment
    instance = linker.instantiate(store, module)
    exports = instance.exports(store)
    memory = exports["memory"]

    exports["_initialize"](store)

    def call_with(buffers, fn):
        pointers = []
        for payload in buffers:
            ptr = exports["alloc"](store, len(payload))
            memory.write(store, payload, ptr)
            pointers.append((ptr, len(payload)))
        result = fn(*[x for pair in pointers for x in pair])
        length = int.from_bytes(memory.read(store, result, result + 4), "little")
        response = bytes(memory.read(store, result + 4, result + 4 + length))
        exports["dealloc"](store, result, 4 + length)
        for ptr, length_in in pointers:
            exports["dealloc"](store, ptr, length_in)
        return response

    abi = exports["abi_version"](store)
    ptr = exports["amaru_version"](store)
    length = int.from_bytes(memory.read(store, ptr, ptr + 4), "little")
    version = bytes(memory.read(store, ptr + 4, ptr + 4 + length)).decode()
    exports["dealloc"](store, ptr, 4 + length)
    print(f"abi_version={abi}; {version.strip().replace(chr(10), '; ')}")
    if abi != 1:
        raise SystemExit(f"unexpected abi_version {abi}")

    # required_keys on bytes that are not a transaction answers with an error document {0: 2, 8: msg}.
    keys = call_with([b"\x00", b""], lambda a, b, c, d: exports["required_keys"](store, a, b, c, d))
    if not keys.startswith(b"\xa2\x00\x02"):
        raise SystemExit(f"required_keys did not answer with an error document: {keys[:16].hex()}")

    key_requests = sorted(requests_dir.glob("*.keys-tx.cbor"))
    if not key_requests:
        raise SystemExit(f"no *.keys-tx.cbor in {requests_dir}")
    key_mismatches = []
    for tx_file in key_requests:
        label = tx_file.name[: -len(".keys-tx.cbor")]
        env = (requests_dir / f"{label}.keys-env.cbor").read_bytes()
        expected = (requests_dir / f"{label}.keys-resp.cbor").read_bytes()
        actual = call_with(
            [tx_file.read_bytes(), env], lambda a, b, c, d: exports["required_keys"](store, a, b, c, d)
        )
        if actual != expected:
            key_mismatches.append(label)
    print(f"{len(key_requests) - len(key_mismatches)}/{len(key_requests)} required_keys responses identical to native")
    for label in key_mismatches:
        print(f"MISMATCH (required_keys): {label}")

    requests = sorted(requests_dir.glob("*.req.cbor"))
    if not requests:
        raise SystemExit(f"no *.req.cbor in {requests_dir}")
    mismatches, timings = [], []
    for request in requests:
        label = request.name[: -len(".req.cbor")]
        expected = (requests_dir / f"{label}.resp.cbor").read_bytes()
        started = time.perf_counter()
        actual = call_with([request.read_bytes()], lambda p, n: exports["validate"](store, p, n))
        timings.append(time.perf_counter() - started)
        if actual != expected:
            mismatches.append(label)

    print(
        f"{len(requests) - len(mismatches)}/{len(requests)} responses identical to native; "
        f"median {statistics.median(timings) * 1000:.2f} ms, max {max(timings) * 1000:.2f} ms per validate (wasmtime)"
    )
    for label in mismatches:
        print(f"MISMATCH: {label}")
    sys.exit(1 if mismatches or key_mismatches else 0)


if __name__ == "__main__":
    main()
