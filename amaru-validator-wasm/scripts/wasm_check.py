#!/usr/bin/env python3
"""Check a built amaru_validator.wasm against the ADR-057 interface and purity contract.

Fails (exit 1) unless:
  * every import is a function from `wasi_snapshot_preview1` in ALLOWED_WASI_IMPORTS, and nothing
    else is imported (the module defines and exports its own memory);
  * the interface-v1 exports exist with the expected signatures, plus `memory`;
  * no instruction, type or section needs threads/atomics, shared memory, SIMD, exception handling,
    tail calls, typed function references, GC or memory64. The code section is decoded instruction
    by instruction (not a byte heuristic).

Usage: wasm_check.py path/to/amaru_validator.wasm [--json]
"""
import json
import sys

# What the minimal host implements (ADR-057 invariant 2): clock, random, a stdout/stderr sink,
# proc_exit (-> trap), and empty args/environment.
ALLOWED_WASI_IMPORTS = {
    "args_get", "args_sizes_get",
    "environ_get", "environ_sizes_get",
    "clock_time_get",
    "fd_write",
    "proc_exit",
    "random_get",
    "sched_yield",
}

EXPECTED_EXPORTS = {
    "abi_version": ([], ["i32"]),
    "amaru_version": ([], ["i32"]),
    "alloc": (["i32"], ["i32"]),
    "dealloc": (["i32", "i32"], []),
    "required_keys": (["i32", "i32", "i32", "i32"], ["i32"]),
    "validate": (["i32", "i32"], ["i32"]),
}

FORBIDDEN_TARGET_FEATURES = {
    "atomics", "shared-mem", "simd128", "relaxed-simd", "exception-handling", "tail-call",
    "gc", "memory64", "multimemory", "typed-function-references", "function-references",
}

VALTYPES = {0x7F: "i32", 0x7E: "i64", 0x7D: "f32", 0x7C: "f64", 0x7B: "v128", 0x70: "funcref", 0x6F: "externref"}


class Reader:
    def __init__(self, data, pos=0, end=None):
        self.data = data
        self.pos = pos
        self.end = len(data) if end is None else end

    def byte(self):
        if self.pos >= self.end:
            raise ValueError("unexpected end of data")
        b = self.data[self.pos]
        self.pos += 1
        return b

    def u(self):
        result, shift = 0, 0
        while True:
            b = self.byte()
            result |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                return result

    def s(self):
        result, shift = 0, 0
        while True:
            b = self.byte()
            result |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                if b & 0x40:
                    result -= 1 << shift
                return result

    def skip(self, n):
        self.pos += n

    def name(self):
        n = self.u()
        value = self.data[self.pos:self.pos + n].decode("utf-8", "replace")
        self.pos += n
        return value


class Problems:
    def __init__(self):
        self.items = []
        self.features = set()

    def add(self, message):
        if message not in self.items:
            self.items.append(message)


def limits(r, problems, what):
    flags = r.byte()
    minimum = r.u()
    maximum = r.u() if flags & 1 else None
    if flags & 2:
        problems.add(f"{what}: shared memory (threads)")
    if flags & 4:
        problems.add(f"{what}: memory64")
    return minimum, maximum


def valtype(b, problems, where):
    if b == 0x7B:
        problems.add(f"v128 value type in {where} (SIMD)")
    elif b in (0x70, 0x6F):
        problems.features.add("reference-types")
    elif b not in VALTYPES:
        problems.add(f"unknown/typed-reference value type 0x{b:02x} in {where}")
    return VALTYPES.get(b, hex(b))


def block_type(r, problems):
    b = r.data[r.pos]
    if b == 0x40:
        r.skip(1)
    elif b in VALTYPES:
        r.skip(1)
        valtype(b, problems, "block type")
    else:
        r.s()  # type index (s33): multi-value block
        problems.features.add("multivalue")


def memarg(r, problems):
    align = r.u()
    if align & 0x40:
        problems.add("multi-memory memarg")
        r.u()
    r.u()


FORBIDDEN_OPCODES = {
    0x06: "exception handling (try)", 0x07: "exception handling (catch)", 0x08: "exception handling (throw)",
    0x09: "exception handling (rethrow)", 0x0A: "exception handling (throw_ref)",
    0x18: "exception handling (delegate)", 0x19: "exception handling (catch_all)",
    0x1F: "exception handling (try_table)",
    0x12: "tail call (return_call)", 0x13: "tail call (return_call_indirect)",
    0x14: "typed function references (call_ref)", 0x15: "tail call (return_call_ref)",
    0xD3: "GC (ref.eq)", 0xD4: "typed function references (ref.as_non_null)",
    0xD5: "typed function references (br_on_null)", 0xD6: "typed function references (br_on_non_null)",
    0xFB: "GC instructions", 0xFD: "SIMD instructions", 0xFE: "threads/atomics instructions",
}


def instructions(r, problems):
    while r.pos < r.end:
        op = r.byte()
        if op in FORBIDDEN_OPCODES:
            problems.add(f"opcode 0x{op:02x}: {FORBIDDEN_OPCODES[op]}")
            return  # cannot decode further immediates reliably
        if op in (0x02, 0x03, 0x04):
            block_type(r, problems)
        elif op in (0x0C, 0x0D, 0x10, 0x20, 0x21, 0x22, 0x23, 0x24, 0xD2):
            r.u()
        elif op in (0x25, 0x26):
            r.u()
            problems.features.add("reference-types")
        elif op == 0x0E:
            for _ in range(r.u()):
                r.u()
            r.u()
        elif op == 0x11:
            r.u()
            if r.u() != 0:
                problems.features.add("reference-types")
        elif op == 0x1C:
            for _ in range(r.u()):
                valtype(r.byte(), problems, "select")
        elif 0x28 <= op <= 0x3E:
            memarg(r, problems)
        elif op in (0x3F, 0x40):
            if r.u() != 0:
                problems.add("multi-memory memory.size/grow")
        elif op == 0x41:
            r.s()
        elif op == 0x42:
            r.s()
        elif op == 0x43:
            r.skip(4)
        elif op == 0x44:
            r.skip(8)
        elif op == 0xD0:
            r.s()
            problems.features.add("reference-types")
        elif op == 0xFC:
            sub = r.u()
            if sub <= 7:
                problems.features.add("nontrapping-fptoint")
            elif sub == 8:
                r.u(); r.u(); problems.features.add("bulk-memory")
            elif sub == 9:
                r.u(); problems.features.add("bulk-memory")
            elif sub in (10, 12, 14):
                r.u(); r.u(); problems.features.add("bulk-memory")
            elif sub in (11, 13):
                r.u(); problems.features.add("bulk-memory")
            elif sub in (15, 16, 17):
                r.u(); problems.features.add("reference-types")
            else:
                problems.add(f"unknown 0xFC sub-opcode {sub}")
                return
        elif 0xC0 <= op <= 0xC4:
            problems.features.add("sign-ext")
        elif op in (0x00, 0x01, 0x05, 0x0B, 0x0F, 0x1A, 0x1B, 0xD1) or 0x45 <= op <= 0xBF:
            pass
        else:
            problems.add(f"unknown opcode 0x{op:02x}")
            return


def const_expr(r, problems, where):
    """Walk a constant expression (global initialiser, data offset) up to its `end`."""
    while True:
        op = r.byte()
        if op == 0x0B:
            return
        if op in (0x41, 0x42):
            r.s()
        elif op == 0x43:
            r.skip(4)
        elif op == 0x44:
            r.skip(8)
        elif op in (0x23, 0xD2):
            r.u()
        elif op == 0xD0:
            r.s()
        elif op in (0x6A, 0x6B, 0x6C, 0x7C, 0x7D, 0x7E):
            problems.features.add("extended-const")
        else:
            problems.add(f"unexpected opcode 0x{op:02x} in {where} constant expression")
            return


def inspect(path):
    data = open(path, "rb").read()
    if data[:4] != b"\0asm" or data[4:8] != b"\x01\0\0\0":
        raise SystemExit(f"{path}: not a wasm v1 module")
    problems = Problems()
    types, imports, exports, memories, custom = [], [], [], [], []
    func_types, import_func_count = [], 0
    target_features = []

    r = Reader(data, 8)
    while r.pos < len(data):
        section = r.byte()
        size = r.u()
        body = Reader(data, r.pos, r.pos + size)
        r.skip(size)
        if section == 0:
            name = body.name()
            custom.append(name)
            if name == "target_features":
                for _ in range(body.u()):
                    prefix = chr(body.byte())
                    target_features.append(prefix + body.name())
        elif section == 1:
            for _ in range(body.u()):
                form = body.byte()
                if form != 0x60:
                    problems.add(f"non-function type form 0x{form:02x} (GC)")
                    break
                params = [valtype(body.byte(), problems, "type") for _ in range(body.u())]
                results = [valtype(body.byte(), problems, "type") for _ in range(body.u())]
                if len(results) > 1:
                    problems.features.add("multivalue")
                types.append((params, results))
        elif section == 2:
            for _ in range(body.u()):
                module, field, kind = body.name(), body.name(), body.byte()
                if kind == 0:
                    imports.append((module, field, "func", types[body.u()]))
                    import_func_count += 1
                elif kind == 1:
                    valtype(body.byte(), problems, "imported table")
                    limits(body, problems, "imported table")
                    imports.append((module, field, "table", None))
                elif kind == 2:
                    limits(body, problems, "imported memory")
                    imports.append((module, field, "memory", None))
                elif kind == 3:
                    valtype(body.byte(), problems, "imported global")
                    body.byte()
                    imports.append((module, field, "global", None))
                else:
                    imports.append((module, field, f"kind {kind}", None))
                    problems.add(f"import {module}::{field} of kind {kind} (exception tag?)")
                    break
        elif section == 3:
            func_types = [body.u() for _ in range(body.u())]
        elif section == 5:
            for _ in range(body.u()):
                memories.append(limits(body, problems, "memory"))
        elif section == 6:
            for i in range(body.u()):
                valtype(body.byte(), problems, "global")
                body.byte()
                const_expr(body, problems, f"global {i}")
        elif section == 11:
            for i in range(body.u()):
                flags = body.u()
                if flags == 2:
                    if body.u() != 0:
                        problems.add("multi-memory data segment")
                if flags in (0, 2):
                    const_expr(body, problems, f"data segment {i}")
                body.skip(body.u())
        elif section == 7:
            for _ in range(body.u()):
                name, kind, index = body.name(), body.byte(), body.u()
                exports.append((name, {0: "func", 1: "table", 2: "memory", 3: "global"}.get(kind, kind), index))
        elif section == 10:
            for i in range(body.u()):
                fsize = body.u()
                fn = Reader(data, body.pos, body.pos + fsize)
                body.skip(fsize)
                for _ in range(fn.u()):
                    fn.u()
                    valtype(fn.byte(), problems, f"locals of function {i}")
                instructions(fn, problems)
        elif section == 13:
            problems.add("tag section (exception handling)")

    for feature in target_features:
        if feature[0] in "+=" and feature[1:] in FORBIDDEN_TARGET_FEATURES:
            problems.add(f"target_features declares {feature[1:]}")

    for module, field, kind, _ in imports:
        if module != "wasi_snapshot_preview1" or kind != "func":
            problems.add(f"non-WASI import {module}::{field} [{kind}]")
        elif field not in ALLOWED_WASI_IMPORTS:
            problems.add(f"WASI import outside the minimal host: {field}")

    export_map = {name: (kind, index) for name, kind, index in exports}
    if export_map.get("memory", (None,))[0] != "memory":
        problems.add("module does not export its memory as `memory`")
    for name, signature in EXPECTED_EXPORTS.items():
        if name not in export_map or export_map[name][0] != "func":
            problems.add(f"missing export `{name}`")
            continue
        index = export_map[name][1] - import_func_count
        actual = types[func_types[index]] if 0 <= index < len(func_types) else None
        if actual != signature:
            problems.add(f"export `{name}` has signature {actual}, expected {signature}")

    return {
        "file": path,
        "size_bytes": len(data),
        "imports": [f"{m}::{f}" for m, f, _, _ in imports],
        "exports": sorted(name for name, _, _ in exports),
        "memories": memories,
        "custom_sections": custom,
        "target_features": target_features,
        "features_used": sorted(problems.features),
        "functions": len(func_types),
        "problems": problems.items,
    }


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    report = inspect(sys.argv[1])
    if "--json" in sys.argv:
        print(json.dumps(report, indent=2))
    else:
        print(f"file:            {report['file']} ({report['size_bytes']:,} bytes, {report['functions']} functions)")
        print(f"imports ({len(report['imports'])}):   {', '.join(report['imports'])}")
        print(f"exports:         {', '.join(report['exports'])}")
        print(f"memories:        {report['memories']}")
        print(f"target_features: {', '.join(report['target_features']) or '(section stripped)'}")
        print(f"features used:   {', '.join(report['features_used']) or 'MVP only'}")
        for problem in report["problems"]:
            print(f"PROBLEM: {problem}")
    if report["problems"]:
        sys.exit(1)
    print("OK: WASI p1 imports only, interface v1 exports present, no threads/atomics/SIMD/EH/tail-call/GC")


if __name__ == "__main__":
    main()
