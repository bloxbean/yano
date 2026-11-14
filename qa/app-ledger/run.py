#!/usr/bin/env python3
"""Focused #245 regression gates. Does not qualify, publish, or release Yano."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
GATES = {
    "host": [":host-api:test", ":runtime:test", "--tests", "*DefaultN2NExtensionRegistryTest",
             "--tests", "*DefaultHostLifecycleRegistryTest", "--tests", "*RuntimeKernelStagesTest",
             "--tests", "*PluginManagerTest", "--tests", "*PriorCompiledPluginCompatibilityTest",
             "--tests", "*ExtractedApiOwnershipTest", "--tests", "*BlockBodyRetentionRegistryTest",
             ":runtime:checkAppLedgerBinaryCompatibility", ":runtime:checkAppLedgerRuntimeCompatibility"],
    "standalone": [":core-api:test", "--tests", "*AppLedgerOperatingProfileTest",
                   ":runtime:test", "--tests", "*AppLedgerAssemblyTest"],
    "adapter": [":app:test", "--tests", "*CardanoAvailabilityFilterTest", "--tests", "*YanoProducerTest"],
    "consensus": [":runtime:test", "--tests", "org.yanoproject.runtime.appchain.*"],
    "configuration": [":core-api:test", ":appchain-config:test"],
    "hybrid": [":runtime:test", "--tests", "*AppChainManagerSharedTransportTest",
               "--tests", "*SharedAppTransportTest", "--tests", "*AppChainL1SubscriptionLifecycleTest",
               "--tests", "*ScriptAnchorServiceTest", "--tests", "*L1EpochObservationCoordinatorTest",
               "--tests", "*AppChainSubsystemGenerationLifecycleTest", "--tests", "*AppPeerClientLifecycleTest"],
    "plugins": [":runtime:test", "--tests", "org.yanoproject.runtime.plugins.*",
                ":runtime:pluginCatalogSemanticsTest"],
}

# Each named suite must execute: unrelated passing tests cannot mask a missing gate.
REQUIRED = {
    "host": {"DefaultHostLifecycleRegistryTest", "RuntimeKernelStagesTest", "HostApiVersionTest", "DefaultN2NExtensionRegistryTest", "PluginManagerTest", "PriorCompiledPluginCompatibilityTest", "ExtractedApiOwnershipTest", "BlockBodyRetentionRegistryTest"},
    "standalone": {"AppLedgerOperatingProfileTest", "AppLedgerAssemblyTest"},
    "adapter": {"CardanoAvailabilityFilterTest", "YanoProducerTest"},
    "consensus": {"AppChainEngineIdentityValidationTest", "AppChainEngineLifecycleTest"},
    "configuration": {"AppLedgerOperatingProfileTest", "AppChainConfigParserTest"},
    "hybrid": {"AppChainManagerSharedTransportTest", "SharedAppTransportTest",
               "AppChainL1SubscriptionLifecycleTest", "ScriptAnchorServiceTest",
               "L1EpochObservationCoordinatorTest", "AppChainSubsystemGenerationLifecycleTest",
               "AppPeerClientLifecycleTest"},
    "plugins": {"PluginManagerTest"},
}


def source_fingerprint():
    paths = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT)
    digest = hashlib.sha256()
    for name in sorted(set(paths.split(b"\0")) - {b""}):
        path = ROOT / os.fsdecode(name)
        digest.update(name + b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest() if path.is_file() else b"deleted")
    return digest.hexdigest()


def run(command, log, timeout):
    with log.open("wb") as output:
        child = subprocess.Popen(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT,
                                 start_new_session=True)
        try:
            return child.wait(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
            return 124


def collect_reports(destination, since):
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    suites = set()
    for module in ("host-api", "core-api", "appchain/appchain-config", "runtime", "app"):
        source = ROOT / module / "build/test-results"
        for xml in source.glob("*/TEST-*.xml"):
            if xml.stat().st_mtime < since:
                continue
            target = destination / module / xml.relative_to(source)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(xml, target)
            suite = ET.parse(xml).getroot()
            if int(suite.get("tests", "0")) > int(suite.get("skipped", "0")):
                suites.add(suite.get("name", "").rsplit(".", 1)[-1])
            for key in totals:
                totals[key] += int(suite.get(key, "0"))
    return totals, suites


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gate", choices=["all", *GATES], default="all")
    parser.add_argument("--out", type=Path)
    parser.add_argument("--timeout", type=int, default=1200, help="Seconds per gate")
    parser.add_argument("--gradle-arg", action="append", default=[], help="Repeat; use --gradle-arg=-Pkey=value")
    args = parser.parse_args()
    output = args.out or ROOT / "qa/results" / ("app-ledger-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    diff = subprocess.check_output(["git", "diff", "HEAD", "--binary"], cwd=ROOT)
    report = {"schemaVersion": 1, "kind": "incremental-regression", "commit": revision,
              "trackedDiffSha256": hashlib.sha256(diff).hexdigest(),
              "sourceSha256": source_fingerprint(),
              "gradleArgumentsSha256": hashlib.sha256(json.dumps(args.gradle_arg).encode()).hexdigest(), "gates": []}
    names = list(GATES) if args.gate == "all" else [args.gate]
    for name in names:
        destination = output / name
        destination.mkdir()
        command = [str(ROOT / "gradlew"), "--console=plain", "--no-parallel", "--rerun-tasks",
                   *args.gradle_arg, ":runtime:writeAppLedgerArtifactInputs", *GATES[name]]
        print(f"Running {name}; log: {destination / 'gradle.log'}", flush=True)
        started = time.time()
        code = run(command, destination / "gradle.log", args.timeout)
        counts, suites = collect_reports(destination / "junit", started)
        required_reports = ["artifact-inputs.json"]
        if name == "host":
            required_reports += ["api-compatibility.xml", "api-baseline.json", "runtime-compatibility.xml", "runtime-baseline.json"]
        missing_reports = []
        for filename in required_reports:
            source = ROOT / "runtime/build/reports/app-ledger" / filename
            if not source.is_file() or source.stat().st_mtime < started:
                missing_reports.append(filename)
            else:
                shutil.copy2(source, destination / filename)
        # A successful Gradle invocation with zero executed tests is not a passing regression gate.
        missing = sorted(REQUIRED[name] - suites)
        passed = (not missing_reports and not missing and code == 0
                  and counts["tests"] > counts["skipped"]
                  and not counts["failures"] and not counts["errors"])
        report["gates"].append({"name": name, "passed": passed, "exitCode": code,
                                 "elapsedSeconds": round(time.time() - started, 2),
                                 "missingVerificationReports": missing_reports, "missingSuites": missing, "executedSuites": sorted(suites), **counts})
        (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
        print(f"{name}: {'PASS' if passed else 'FAIL'} ({counts})", flush=True)
    report["sourceUnchanged"] = source_fingerprint() == report["sourceSha256"]
    passed = report["sourceUnchanged"] and all(gate["passed"] for gate in report["gates"])
    report["passed"] = passed
    (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    lines = [f"# App-ledger incremental regressions: {'PASS' if passed else 'FAIL'}", "", f"Commit: `{revision}`", "",
             "| Gate | Result | Tests | Skipped | Seconds |", "| --- | --- | ---: | ---: | ---: |"]
    for gate in report["gates"]:
        lines.append(f"| {gate['name']} | {'PASS' if gate['passed'] else 'FAIL'} | {gate['tests']} | {gate['skipped']} | {gate['elapsedSeconds']} |")
    for gate in report["gates"]:
        if not gate["passed"]:
            lines.extend(["", f"{gate['name']}: exit {gate['exitCode']}; "
                          f"missing suites: {gate['missingSuites']}; "
                          f"missing verification reports: {gate['missingVerificationReports']}."])
    if not report["sourceUnchanged"]:
        lines.extend(["", "Source changed during the run; results do not describe one source revision."])
    lines.extend(["", "This is focused development validation, not the final coordinated release qualification.", ""])
    (output / "report.md").write_text("\n".join(lines))
    print(f"Report: {output / 'report.md'}", flush=True)
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
