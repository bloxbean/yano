#!/usr/bin/env python3
"""Create, migrate, validate, and render showcase deployment identity."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import stat
import sys


LIGHT_CHAINS = [
    "orders-chain", "registry-chain", "approvals-chain", "balances-chain",
    "documents-chain", "workflow-chain", "roles-chain", "payments-chain",
]
CHAIN_ID = re.compile(r"[A-Za-z0-9._~-]{1,128}")
HEX_32 = re.compile(r"[0-9a-f]{64}")


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def requested_anchor_chains(values: list[str], configured: list[str]) -> list[str]:
    requested: list[str] = []
    for value in values:
        if value == "all":
            requested.extend(configured)
        else:
            requested.extend(value.split(","))
    if not requested:
        requested = ["workflow-chain"]
    unknown = [chain for chain in requested if chain not in configured]
    if unknown:
        raise ValueError(f"unknown anchor chain: {unknown[0]}")
    if any(not CHAIN_ID.fullmatch(chain) for chain in requested):
        raise ValueError("anchor chain id is invalid")
    return [chain for chain in configured if chain in set(requested)]


def anchor_scope(anchor: dict, configured: list[str]) -> list[str]:
    if not anchor.get("enabled"):
        return []
    if "chainIds" in anchor:
        chains = anchor["chainIds"]
    elif anchor.get("chainId"):
        chains = [anchor["chainId"]]
    else:
        # Legacy cluster markers used an absent chainId to mean every chain.
        chains = configured
    if (not isinstance(chains, list) or not chains or len(chains) != len(set(chains))
            or any(chain not in configured for chain in chains)):
        raise ValueError("invalid retained anchor scope")
    return [chain for chain in configured if chain in set(chains)]


def anchor_identity(enabled: bool, mode: str, chains: list[str], key_reference: str | None,
                    signer_fingerprint: str | None = None) -> dict:
    anchor = {
        "enabled": enabled,
        "mode": mode if enabled else "none",
    }
    if signer_fingerprint is not None:
        anchor["signerFingerprint"] = signer_fingerprint if enabled else None
        if not enabled:
            anchor["mode"] = None
    if enabled:
        if len(chains) == 1:
            anchor["chainId"] = chains[0]
        else:
            anchor["chainIds"] = chains
    elif signer_fingerprint is None:
        anchor["chainId"] = None
    if signer_fingerprint is None:
        anchor["keyReference"] = key_reference
    return anchor


def document(args: argparse.Namespace) -> dict:
    config = pathlib.Path(args.config).resolve()
    plugin = pathlib.Path(args.plugin).resolve()
    chains = LIGHT_CHAINS if args.profile == "light" else []
    selected = requested_anchor_chains(args.anchor_chain, chains) if args.anchor else []
    return {
        "schemaVersion": 2 if len(selected) > 1 else 1,
        "kind": "yano.showcase.deployment",
        "showcaseVersion": args.version,
        "profile": args.profile,
        "variant": args.variant,
        "network": args.network,
        "protocolMagic": 1 if args.network == "preprod" else 42,
        "bootstrapNodeCount": args.nodes,
        "bootstrapThreshold": args.threshold,
        "httpBase": args.http_base,
        "serverBase": args.server_base,
        "runtime": "jvm",
        "chainIds": chains,
        "configSha256": sha256(config),
        "pluginSha256": sha256(plugin),
        "anchor": anchor_identity(
            args.anchor,
            args.anchor_mode,
            selected,
            str(pathlib.Path(args.anchor_key_file).resolve()) if args.anchor_key_file else None,
        ),
    }


def canonical(value: dict) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def secure_existing(path: pathlib.Path) -> bytes:
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid() or before.st_nlink != 1:
        raise ValueError("deployment marker is not a launcher-owned regular file")
    if stat.S_IMODE(before.st_mode) not in (0o400, 0o600) or not 0 < before.st_size <= 65536:
        raise ValueError("deployment marker has unsafe permissions or size")
    value = path.read_bytes()
    after = path.lstat()
    stable = ("st_dev", "st_ino", "st_uid", "st_mode", "st_nlink", "st_size")
    if len(value) != before.st_size or any(
            getattr(before, field) != getattr(after, field) for field in stable):
        raise ValueError("deployment marker changed while reading")
    return value


def unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("identity contains a duplicate key")
        result[key] = value
    return result


def parsed_identity(path: pathlib.Path, kind: str) -> dict:
    raw = secure_existing(path)
    parsed = json.loads(
        raw.decode("utf-8"),
        object_pairs_hook=unique_object,
        parse_constant=lambda _value: (_ for _ in ()).throw(ValueError("non-finite number")),
    )
    if not isinstance(parsed, dict) or parsed.get("kind") != kind:
        raise ValueError(f"unexpected identity kind in {path}")
    if parsed.get("schemaVersion") not in (1, 2) or raw != canonical(parsed):
        raise ValueError(f"identity is not canonical or has an unsupported schema: {path}")
    return parsed


def write_atomic(path: pathlib.Path, encoded: bytes) -> None:
    if path.exists() and secure_existing(path) == encoded:
        return
    temporary = path.with_name(path.name + f".tmp.{os.getpid()}")
    descriptor = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        os.write(descriptor, encoded)
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    os.replace(temporary, path)
    os.chmod(path, 0o600)
    directory = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def read_anchor_seed(path: str, network: str) -> str:
    if not path:
        if network != "devnet":
            raise ValueError("public-network anchor enable requires --anchor-key-file")
        return "30" * 32
    requested = pathlib.Path(path)
    if requested.is_symlink():
        raise ValueError("anchor key file must not be a symbolic link")
    source = requested.resolve()
    info = source.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_nlink != 1
            or stat.S_IMODE(info.st_mode) not in (0o400, 0o600)):
        raise ValueError("anchor key file must be a launcher-owned 0400/0600 regular file")
    parent = source.parent.lstat()
    if (not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid()
            or stat.S_IMODE(parent.st_mode) & 0o077):
        raise ValueError("anchor key parent must be launcher-owned and owner-only")
    raw = source.read_bytes()
    if not re.fullmatch(rb"[0-9a-fA-F]{64}\n?", raw):
        raise ValueError("anchor key file must contain exactly one 32-byte hex seed")
    return raw.rstrip(b"\n").decode("ascii").lower()


def cluster_fingerprint(seed: str) -> str:
    return hashlib.sha256(
        b"yano-cluster-anchor-signer-v1\0" + seed.encode("ascii")
    ).hexdigest()


def validate_deployment_identity(document: dict) -> None:
    expected = {
        "schemaVersion", "kind", "showcaseVersion", "profile", "variant", "network",
        "protocolMagic", "bootstrapNodeCount", "bootstrapThreshold", "httpBase",
        "serverBase", "runtime", "chainIds", "configSha256", "pluginSha256", "anchor",
    }
    anchor = document.get("anchor")
    if (set(document) != expected or document.get("profile") != "light"
            or document.get("network") not in ("devnet", "preprod")
            or document.get("protocolMagic") != (1 if document.get("network") == "preprod" else 42)
            or document.get("chainIds") != LIGHT_CHAINS
            or type(document.get("bootstrapNodeCount")) is not int
            or type(document.get("bootstrapThreshold")) is not int
            or not 1 <= document["bootstrapThreshold"] <= document["bootstrapNodeCount"] <= 16
            or not isinstance(anchor, dict)
            or type(anchor.get("enabled")) is not bool):
        raise ValueError("showcase deployment identity is malformed")
    if anchor["enabled"]:
        if (anchor.get("mode") not in ("script", "metadata")
                or set(anchor) not in (
                    {"enabled", "mode", "chainId", "keyReference"},
                    {"enabled", "mode", "chainIds", "keyReference"})):
            raise ValueError("showcase anchor identity is malformed")
        selected = anchor_scope(anchor, LIGHT_CHAINS)
        if document.get("schemaVersion") != (2 if len(selected) > 1 else 1):
            raise ValueError("showcase anchor identity has an inconsistent schema")
    elif (set(anchor) != {"enabled", "mode", "chainId", "keyReference"}
          or document.get("schemaVersion") != 1 or anchor.get("mode") != "none"
          or anchor.get("chainId") is not None or anchor.get("keyReference") is not None):
        raise ValueError("disabled showcase anchor identity is malformed")


def validate_cluster_identity(document: dict, configured: list[str]) -> None:
    expected = {"schemaVersion", "kind", "network", "memberCount", "members", "threshold",
                "proposer", "chainIds", "anchor"}
    members = document.get("members")
    anchor = document.get("anchor")
    if (set(document) != expected or document.get("network") not in
            ("devnet", "preprod", "preview", "mainnet", "sanchonet")
            or document.get("chainIds") != configured
            or type(document.get("memberCount")) is not int
            or not isinstance(members, list) or len(members) != document["memberCount"]
            or not members or len(members) != len(set(members))
            or any(not isinstance(member, str) or not HEX_32.fullmatch(member) for member in members)
            or type(document.get("threshold")) is not int
            or not 1 <= document["threshold"] <= len(members)
            or document.get("proposer") not in members
            or not isinstance(anchor, dict) or type(anchor.get("enabled")) is not bool):
        raise ValueError("cluster app-chain identity is malformed")
    if anchor["enabled"]:
        if (anchor.get("mode") not in ("script", "metadata")
                or not isinstance(anchor.get("signerFingerprint"), str)
                or not HEX_32.fullmatch(anchor["signerFingerprint"])
                or set(anchor) not in (
                    {"enabled", "mode", "signerFingerprint"},
                    {"enabled", "mode", "signerFingerprint", "chainId"},
                    {"enabled", "mode", "signerFingerprint", "chainIds"})):
            raise ValueError("cluster anchor identity is malformed")
        selected = anchor_scope(anchor, configured)
        explicit_scope = "chainId" in anchor or "chainIds" in anchor
        if (explicit_scope
                and document.get("schemaVersion") != (2 if len(selected) > 1 else 1)):
            raise ValueError("cluster anchor identity has an inconsistent schema")
    elif (set(anchor) != {"enabled", "mode", "signerFingerprint"}
          or document.get("schemaVersion") != 1 or anchor.get("mode") is not None
          or anchor.get("signerFingerprint") is not None):
        raise ValueError("disabled cluster anchor identity is malformed")


def parse_cluster_env(path: pathlib.Path) -> dict[str, str]:
    raw = secure_existing(path).decode("ascii")
    values: dict[str, str] = {}
    allowed = {"NETWORK", "ENABLE_ANCHOR", "ANCHOR_MODE", "ANCHOR_CHAIN",
               "ANCHOR_CHAINS", "HTTP_BASE", "SERVER_BASE"}
    for line in raw.splitlines():
        if "=" not in line:
            raise ValueError("cluster environment record contains an invalid line")
        key, value = line.split("=", 1)
        if key not in allowed or key in values:
            raise ValueError("cluster environment record contains an unknown or duplicate key")
        values[key] = value
    required = {"NETWORK", "ENABLE_ANCHOR", "ANCHOR_MODE", "HTTP_BASE", "SERVER_BASE"}
    if not required.issubset(values) or {"ANCHOR_CHAIN", "ANCHOR_CHAINS"}.issubset(values):
        raise ValueError("cluster environment record is incomplete or ambiguous")
    if (values["NETWORK"] not in ("devnet", "preprod", "preview", "mainnet", "sanchonet")
            or values["ENABLE_ANCHOR"] not in ("0", "1")
            or values["ANCHOR_MODE"] not in ("script", "metadata")
            or not values["HTTP_BASE"].isdigit() or not values["SERVER_BASE"].isdigit()
            or not 1 <= int(values["HTTP_BASE"]) <= 65535
            or not 1 <= int(values["SERVER_BASE"]) <= 65535):
        raise ValueError("cluster environment record contains invalid values")
    return values


def env_anchor_scope(values: dict[str, str], configured: list[str]) -> list[str]:
    if values["ENABLE_ANCHOR"] == "0":
        return []
    encoded = values.get("ANCHOR_CHAINS", values.get("ANCHOR_CHAIN", ""))
    return configured if not encoded else requested_anchor_chains([encoded], configured)


def encode_cluster_env(values: dict[str, str], selected: list[str], mode: str) -> bytes:
    lines = [
        f"NETWORK={values['NETWORK']}",
        "ENABLE_ANCHOR=1",
        f"ANCHOR_MODE={mode}",
        f"ANCHOR_CHAINS={','.join(selected)}",
        f"HTTP_BASE={values['HTTP_BASE']}",
        f"SERVER_BASE={values['SERVER_BASE']}",
    ]
    return ("\n".join(lines) + "\n").encode("ascii")


def migrate_anchor(args: argparse.Namespace) -> None:
    marker = pathlib.Path(args.marker)
    deployment = parsed_identity(marker, "yano.showcase.deployment")
    validate_deployment_identity(deployment)
    if not args.config or not args.plugin:
        raise ValueError("anchor-enable requires --config and --plugin")
    if (sha256(pathlib.Path(args.config).resolve()) != deployment.get("configSha256")
            or sha256(pathlib.Path(args.plugin).resolve()) != deployment.get("pluginSha256")):
        raise ValueError("packaged config/plugin differs from the retained showcase identity")
    configured = deployment.get("chainIds")
    if (deployment.get("profile") != "light" or not isinstance(configured, list)
            or configured != LIGHT_CHAINS):
        raise ValueError("anchor enable is supported only for the maintained light profile")
    current_anchor = deployment.get("anchor")
    if not isinstance(current_anchor, dict):
        raise ValueError("deployment anchor identity is malformed")
    current = anchor_scope(current_anchor, configured)
    requested = requested_anchor_chains(args.anchor_chain, configured)
    selected = [chain for chain in configured if chain in set(current + requested)]
    mode = current_anchor.get("mode") if current else args.anchor_mode
    if mode not in ("script", "metadata"):
        raise ValueError("anchor mode must be script or metadata")
    if current and args.anchor_mode != mode:
        raise ValueError("retained anchor mode cannot be changed")
    retained_key = current_anchor.get("keyReference")
    requested_key = str(pathlib.Path(args.anchor_key_file).resolve()) if args.anchor_key_file else None
    if current and requested_key and requested_key != retained_key:
        raise ValueError("retained anchor key reference cannot be changed")
    key_reference = retained_key or requested_key
    seed = read_anchor_seed(key_reference or "", deployment.get("network"))

    cluster_marker_path = pathlib.Path(args.cluster_marker)
    cluster = parsed_identity(cluster_marker_path, "yano.cluster.appchain-identity")
    validate_cluster_identity(cluster, configured)
    if cluster.get("chainIds") != configured or cluster.get("network") != deployment.get("network"):
        raise ValueError("cluster identity does not match the showcase deployment")
    cluster_anchor = cluster.get("anchor")
    if not isinstance(cluster_anchor, dict):
        raise ValueError("cluster anchor identity is malformed")
    cluster_current = anchor_scope(cluster_anchor, configured)
    if cluster_current not in (current, selected):
        raise ValueError("cluster and showcase retained anchor scopes disagree")
    expected_fingerprint = cluster_fingerprint(seed)
    retained_fingerprint = cluster_anchor.get("signerFingerprint")
    if cluster_current and retained_fingerprint != expected_fingerprint:
        raise ValueError("selected anchor key does not match the retained cluster signer")

    cluster["schemaVersion"] = 2 if len(selected) > 1 else 1
    cluster["anchor"] = anchor_identity(
        True, mode, selected, None, retained_fingerprint or expected_fingerprint)

    env_path = pathlib.Path(args.cluster_env)
    environment = parse_cluster_env(env_path)
    environment_current = env_anchor_scope(environment, configured)
    if environment_current not in (current, selected):
        raise ValueError("cluster environment and showcase retained anchor scopes disagree")

    deployment["schemaVersion"] = 2 if len(selected) > 1 else 1
    deployment["anchor"] = anchor_identity(True, mode, selected, key_reference)

    # Each replacement is durable and the operation is idempotent. Write the
    # lower-level cluster records first; a crash before the final deployment
    # marker is repaired by rerunning the same additive command.
    write_atomic(cluster_marker_path, canonical(cluster))
    write_atomic(env_path, encode_cluster_env(environment, selected, mode))
    write_atomic(marker, canonical(deployment))
    print(",".join(selected))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("ensure", "show", "export", "anchor-enable"))
    parser.add_argument("--marker", required=True)
    parser.add_argument("--version", default="development")
    parser.add_argument("--profile", default="light")
    parser.add_argument("--variant", default="default")
    parser.add_argument("--network", default="devnet")
    parser.add_argument("--nodes", type=int, default=3)
    parser.add_argument("--threshold", type=int, default=2)
    parser.add_argument("--http-base", type=int, default=7070)
    parser.add_argument("--server-base", type=int, default=13337)
    parser.add_argument("--config")
    parser.add_argument("--plugin")
    parser.add_argument("--anchor", action="store_true")
    parser.add_argument("--anchor-mode", default="script")
    parser.add_argument("--anchor-chain", action="append", default=[])
    parser.add_argument("--anchor-key-file", default="")
    parser.add_argument("--cluster-marker")
    parser.add_argument("--cluster-env")
    parser.add_argument("--output")
    args = parser.parse_args()
    marker = pathlib.Path(args.marker)
    if args.action == "anchor-enable":
        if not args.cluster_marker or not args.cluster_env:
            raise ValueError("anchor-enable requires --cluster-marker and --cluster-env")
        migrate_anchor(args)
        return
    if args.action == "show":
        parsed = json.loads(secure_existing(marker))
        if parsed.get("anchor", {}).get("keyReference"):
            parsed["anchor"]["keyReference"] = "<redacted-owner-file>"
        print(json.dumps(parsed, indent=2, sort_keys=True))
        return
    if args.action == "export":
        parsed = json.loads(secure_existing(marker))
        if parsed.get("anchor", {}).get("keyReference"):
            parsed["anchor"]["keyReference"] = "<redacted-owner-file>"
        encoded = json.dumps(parsed, indent=2, sort_keys=True) + "\n"
        if args.output:
            pathlib.Path(args.output).write_text(encoded, encoding="utf-8")
        else:
            print(encoded, end="")
        return
    if not args.config or not args.plugin:
        raise ValueError("ensure requires --config and --plugin")
    expected = canonical(document(args))
    marker.parent.mkdir(parents=True, exist_ok=True)
    if marker.exists():
        actual = secure_existing(marker)
        if actual != expected:
            raise ValueError("requested configuration differs from retained showcase identity; "
                             "use a new --instance or reset the existing instance")
        return
    write_atomic(marker, expected)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"error: {failure}", file=sys.stderr)
        raise SystemExit(2)
