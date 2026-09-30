#!/usr/bin/env python3
"""Read-only comparison of a Yano node's ledger state with Koios (db-sync).

Usage:
    koios_compare.py --network preprod --yano http://localhost:7171 \\
        [--from-epoch N] [--to-epoch M] [--drep-epochs K] [--out report.md] [--cafile ca.pem]

Only GET requests are sent, to both sides. Exit code 0 = no mismatch, 1 = mismatches, 2 = could not run.

Checks (Yano REST /api/v1 vs Koios /api/v1):
- Treasury, reserves, fees per epoch: /epochs/adapots vs /totals. Same label on both sides: epoch N is the pots
  after the N-1 -> N boundary, and fees at N are the fees of epoch N-1. Yano's first AdaPot has fees = 0
  (pre-bootstrap fees are not tracked), so that one fee value is skipped.
- Deposits per epoch, exactly (ADR-058): Yano `deposits_key + deposits_pool` vs Koios `deposits_stake` (db-sync's
  stake-key plus pool obligations), `deposits_drep` vs `deposits_drep`, `deposits_proposal` vs `deposits_proposal`,
  and `deposits` vs the sum of the three. A Yano AdaPot written before ADR-058 has no categories (its `deposits` is
  key + DRep only); such epochs are listed, not compared.
- Deposit audit at the tip (Yano-internal, /api/debug/deposits): the stake accounts' deposits add up to Yano's
  total_dep minus its DRep deposits, and the pool deposits equal the pool count x pool_deposit (valid while the
  pool deposit parameter has never changed, as on mainnet, preprod and preview). The pool count is
  also shown next to Koios /pool_list (registered or retiring) for information only: Koios still lists pools
  registered and retired in the same transaction, which POOLREAP retired and refunded.
- Registered DReps at the tip: /governance/dreps?status=active vs /drep_list?registered=eq.true.
- DRep distribution totals of the last K epochs: the sum of /governance/dreps/{id}/distribution/{epoch} vs
  /drep_epoch_summary minus the always-abstain and always-no-confidence pseudo DReps; for the latest mismatching
  epoch, per DRep against /drep_voting_power_history.
- Governance proposals: Yano serves only proposals still in governance state, so every Koios proposal's lifecycle
  epochs are checked by the presence and status they imply at Yano's tip epoch, plus type, proposed epoch,
  expiration and deposit where both sides have it. Run the tool at the tip, ideally once per epoch.

Koios: at most one request per --koios-interval seconds (default 1 s), retries with exponential backoff on 429 and
5xx, honouring Retry-After. A run makes a few dozen Koios requests.
"""
import argparse
import datetime
import json
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

KOIOS_URLS = {
    "mainnet": "https://api.koios.rest/api/v1",
    "preprod": "https://preprod.koios.rest/api/v1",
    "preview": "https://preview.koios.rest/api/v1",
}
USER_AGENT = "yano-koios-compare/1.0 (read-only)"
PSEUDO_DREPS = ("drep_always_abstain", "drep_always_no_confidence")
KOIOS_TYPES = {
    "ParameterChange": "parameter_change",
    "HardForkInitiation": "hard_fork_initiation",
    "TreasuryWithdrawals": "treasury_withdrawals",
    "NoConfidence": "no_confidence",
    "NewCommittee": "update_committee",
    "UpdateCommittee": "update_committee",
    "NewConstitution": "new_constitution",
    "InfoAction": "info_action",
}


class FetchError(Exception):
    pass


def log(msg):
    print(msg, file=sys.stderr, flush=True)


class Koios:
    def __init__(self, base, interval, timeout, cafile, max_retries=6):
        self.base = base.rstrip("/")
        self.ctx = ssl.create_default_context(cafile=cafile)
        self.interval = interval
        self.timeout = timeout
        self.max_retries = max_retries
        self._last = 0.0
        self.requests = 0

    def get(self, path, params=None):
        url = f"{self.base}/{path}" + ("?" + urllib.parse.urlencode(params, safe="(),.") if params else "")
        backoff = 2.0
        for attempt in range(self.max_retries + 1):
            time.sleep(max(0.0, self._last + self.interval - time.monotonic()))
            self._last = time.monotonic()
            self.requests += 1
            req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": USER_AGENT})
            try:
                with urllib.request.urlopen(req, timeout=self.timeout, context=self.ctx) as resp:
                    return json.load(resp)
            except urllib.error.HTTPError as e:
                if e.code not in (429, 500, 502, 503, 504) or attempt == self.max_retries:
                    raise FetchError(f"Koios GET {url} -> HTTP {e.code}: {e.read()[:200]!r}")
                retry_after = e.headers.get("Retry-After", "") if e.headers else ""
                delay = float(retry_after) if retry_after.isdigit() else backoff
            except (urllib.error.URLError, TimeoutError) as e:
                if isinstance(getattr(e, "reason", None), ssl.SSLCertVerificationError):
                    raise FetchError(f"Koios GET {url}: TLS verification failed ({e}); pass --cafile "
                                     "(for example /etc/ssl/cert.pem on macOS, or the certifi bundle)")
                if attempt == self.max_retries:
                    raise FetchError(f"Koios GET {url} failed: {e}")
                delay = backoff
            log(f"  Koios {path}: retrying in {delay:.0f} s")
            time.sleep(delay)
            backoff = min(backoff * 2, 60)

    def get_all(self, path, params, page_size=1000):
        rows = []
        while True:
            page = self.get(path, dict(params, limit=page_size, offset=len(rows)))
            rows.extend(page)
            if len(page) < page_size:
                return rows


class Yano:
    def __init__(self, base, timeout):
        self.root = base.rstrip("/")
        self.base = self.root + "/api/v1"
        self.timeout = timeout
        self.requests = 0

    def get(self, path, params=None, allow_404=False, base=None, absent_codes=(404,)):
        url = f"{base or self.base}/{path}" + ("?" + urllib.parse.urlencode(params) if params else "")
        req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": USER_AGENT})
        for attempt in range(3):
            self.requests += 1
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    return json.load(resp)
            except urllib.error.HTTPError as e:
                if e.code in absent_codes and allow_404:
                    return None
                if e.code not in (502, 503, 504) or attempt == 2:
                    raise FetchError(f"Yano GET {url} -> HTTP {e.code}: {e.read()[:200]!r}")
            except (urllib.error.URLError, TimeoutError) as e:
                if attempt == 2:
                    raise FetchError(f"Yano GET {url} failed: {e}")
            time.sleep(1 + attempt)

    def pages(self, path, params):
        rows, page = [], 1
        while True:
            batch = self.get(path, dict(params, count=100, page=page))
            rows.extend(batch)
            if len(batch) < 100:
                return rows
            page += 1


def to_int(v):
    return None if v is None or v == "" else int(v)


def ada(lovelace):
    if lovelace is None:
        return "-"
    q, r = divmod(abs(lovelace), 1_000_000)
    return f"{'-' if lovelace < 0 else ''}{q:,}.{r:06d}"


def ranges(epochs):
    out, start = [], None
    for i, e in enumerate(epochs):
        start = e if start is None else start
        if i + 1 == len(epochs) or epochs[i + 1] != e + 1:
            out.append(str(e) if start == e else f"{start}-{e}")
            start = None
    return ", ".join(out) or "none"


# ---------------------------------------------------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------------------------------------------------

def compare_pots(koios_totals, yano_pots, epochs):
    """Per field, the mismatching epochs with the delta and its change since the previous epoch (new drift)."""
    fields = ("treasury", "reserves", "fees")
    mismatches = {f: [] for f in fields}
    prev = dict.fromkeys(fields, 0)
    first_yano = min(yano_pots, default=None)
    for epoch in epochs:
        k, y = koios_totals[epoch], yano_pots[epoch]
        for f in fields:
            yv, kv = to_int(y.get(f)), to_int(k.get(f))
            if f == "fees" and epoch == first_yano and yv == 0:
                continue
            delta = yv - kv
            if delta:
                mismatches[f].append({"epoch": epoch, "yano": yv, "koios": kv, "delta": delta,
                                      "step": delta - prev[f]})
            prev[f] = delta
    return mismatches


DEPOSIT_CHECKS = (
    ("key + pool", ("deposits_key", "deposits_pool"), ("deposits_stake",)),
    ("DRep", ("deposits_drep",), ("deposits_drep",)),
    ("proposal", ("deposits_proposal",), ("deposits_proposal",)),
    ("total", ("deposits",), ("deposits_stake", "deposits_drep", "deposits_proposal")),
)


def compare_deposits(koios_totals, yano_pots, epochs):
    """Per epoch, the exact category checks that fail, and the epochs whose Yano AdaPot has no categories."""
    rows, legacy = [], []
    for epoch in epochs:
        k, y = koios_totals[epoch], yano_pots[epoch]
        if y.get("deposits_key") is None:
            legacy.append(epoch)
            continue
        for name, yano_fields, koios_fields in DEPOSIT_CHECKS:
            yv = sum(to_int(y.get(f)) or 0 for f in yano_fields)
            kv = sum(to_int(k.get(f)) or 0 for f in koios_fields)
            if yv != kv:
                rows.append({"epoch": epoch, "check": name, "yano": yv, "koios": kv})
    return rows, legacy


def deposit_audit(koios, yano, pool_deposit):
    """Yano-internal deposit checks at the tip, and the pool count next to Koios pool_list (informational)."""
    audit = yano.get("debug/deposits", base=yano.root + "/api", allow_404=True, absent_codes=(404, 503))
    if audit is None:
        return None
    koios_pools = sum(1 for r in koios.get_all("pool_list", {"select": "pool_status"})
                      if r.get("pool_status") in ("registered", "retiring"))
    total, dreps = int(audit["total_deposited"]), int(audit["drep_deposits"])
    pools, pool_count = int(audit["pool_deposits"]), int(audit["pool_count"])
    failures = []
    if not audit["stake_keys_consistent"]:
        failures.append(f"stake accounts' deposits {int(audit['account_deposits']):,} != total_dep {total:,} "
                        f"- DRep deposits {dreps:,}")
    if pools != pool_count * pool_deposit:
        failures.append(f"pool deposits {pools:,} != {pool_count} pools x {pool_deposit:,}")
    return {"audit": audit, "koios_pools": koios_pools, "failures": failures}


def expected_status(p, epoch):
    """The Yano status a Koios proposal implies at `epoch` (db-sync: ratified at R is enacted at R+1; expired at X
    is dropped at X+1)."""
    enacted, dropped = to_int(p.get("enacted_epoch")), to_int(p.get("dropped_epoch"))
    ratified, expired = to_int(p.get("ratified_epoch")), to_int(p.get("expired_epoch"))
    if (to_int(p.get("proposed_epoch")) or 0) > epoch:
        return "future"
    if (enacted is not None and enacted <= epoch) or (dropped is not None and dropped <= epoch):
        return "absent"
    if ratified is not None and ratified <= epoch:
        return "pending_ratified"
    if expired is not None and expired <= epoch:
        return "pending_expired"
    if dropped == epoch + 1:
        return "pending_expired|active"
    return "active"


def compare_proposals(koios_props, yano_props, tip):
    yano_by_id = {(p["tx_hash"], int(p["cert_index"])): p for p in yano_props}
    rows, checked, seen = [], 0, set()
    for kp in koios_props:
        key = (kp["proposal_tx_hash"], int(kp["proposal_index"]))
        seen.add(key)
        expected = expected_status(kp, tip)
        if expected == "future":
            continue
        checked += 1
        yp = yano_by_id.get(key)
        issues = []
        if expected == "absent":
            if yp is not None:
                issues.append(f"in Yano ({yp.get('status')}), but Koios says it left state")
        elif yp is None:
            issues.append(f"missing in Yano, expected {expected}")
        elif yp.get("status") not in expected.split("|"):
            issues.append(f"status {yp.get('status')}, expected {expected}")
        if yp is not None:
            ktype = KOIOS_TYPES.get(kp.get("proposal_type"), kp.get("proposal_type"))
            if yp.get("governance_type") != ktype:
                issues.append(f"type {yp.get('governance_type')} != {ktype}")
            for f in ("proposed_epoch", "expiration", "deposit"):
                yv, kv = to_int(yp.get(f)), to_int(kp.get(f))
                if kv is not None and yv != kv:
                    issues.append(f"{f} {yv} != {kv}")
        if issues:
            rows.append((f"{key[0]}#{key[1]}", kp.get("proposal_type"), "; ".join(issues)))
    for key, yp in yano_by_id.items():
        if key not in seen:
            checked += 1
            rows.append((f"{key[0]}#{key[1]}", yp.get("governance_type"), "unknown to Koios"))
    return rows, checked


def compare_drep_distribution(koios, yano, first, last, workers):
    summary = {int(r["epoch_no"]): r for r in koios.get(
        "drep_epoch_summary", {"and": f"(epoch_no.gte.{first},epoch_no.lte.{last})"})}
    pseudo = {}
    for drep in PSEUDO_DREPS:
        for r in koios.get("drep_voting_power_history",
                           {"_drep_id": drep, "and": f"(epoch_no.gte.{first},epoch_no.lte.{last})"}):
            pseudo.setdefault(int(r["epoch_no"]), []).append(int(r["amount"]))
    drep_ids = [r["drep_id"] for r in yano.pages("governance/dreps", {"status": "all"})]

    def amount(drep_id, epoch):
        r = yano.get(f"governance/dreps/{drep_id}/distribution/{epoch}", allow_404=True)
        return int(r["amount"]) if r else None

    rows = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for epoch in range(first, last + 1):
            if epoch not in summary:
                continue
            log(f"  DRep distribution, epoch {epoch}")
            amounts = pool.map(lambda d: amount(d, epoch), drep_ids)
            ymap = {d: a for d, a in zip(drep_ids, amounts) if a is not None}
            if ymap:
                ps = pseudo.get(epoch, [])
                rows.append({"epoch": epoch, "yano": sum(ymap.values()), "yano_n": len(ymap), "ymap": ymap,
                             "koios": int(summary[epoch]["amount"]) - sum(ps),
                             "koios_n": int(summary[epoch]["dreps"]) - len(ps)})
    bad = [r for r in rows if r["yano"] != r["koios"] or r["yano_n"] != r["koios_n"]]
    diff = None
    if bad:
        r = bad[-1]
        kmap = {x["drep_id"]: int(x["amount"]) for x in koios.get_all(
            "drep_voting_power_history", {"_epoch_no": r["epoch"]}) if x["drep_id"] not in PSEUDO_DREPS}
        diff = {"epoch": r["epoch"], "rows": [(d, r["ymap"].get(d), kmap.get(d))
                                              for d in sorted(set(kmap) | set(r["ymap"]))
                                              if r["ymap"].get(d) != kmap.get(d)]}
    return rows, len(bad), diff


# ---------------------------------------------------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------------------------------------------------

def render(c):
    L = [f"# Yano vs Koios: {c['network']}", "",
         f"- Generated {c['generated']}; Yano `{c['yano_url']}` (tip epoch {c['yano_tip']}), Koios `{c['koios_url']}` "
         f"(tip epoch {c['koios_tip']})",
         f"- Epochs compared: {ranges(c['epochs'])}; Koios epochs after Yano's tip (not compared): "
         f"{ranges(c['not_synced'])}",
         f"- Requests: Yano {c['yano_requests']}, Koios {c['koios_requests']}", "",
         "| Check | Compared | Mismatches |", "|---|---:|---:|"]
    L += [f"| {name} | {compared} | {bad} |" for name, compared, bad in c["summary"]]
    L += ["", f"**{'PASS' if c['total_bad'] == 0 else 'FAIL'}** ({c['total_bad']} mismatching items)", ""]

    if c["gaps"]:
        L += [f"AdaPot missing in Yano at or before its tip: {ranges(c['gaps'])}", ""]
    for f, rows in c["pots"].items():
        if rows:
            L += [f"## {f.capitalize()}", "", f"First divergence: epoch {rows[0]['epoch']}. Epochs where the delta "
                  "changed (the others carry an earlier delta forward):", "",
                  "| Epoch | Yano (ADA) | Koios (ADA) | Yano − Koios (lovelace) | New drift (lovelace) |",
                  "|---:|---:|---:|---:|---:|"]
            L += [f"| {r['epoch']} | {ada(r['yano'])} | {ada(r['koios'])} | {r['delta']:+,} | {r['step']:+,} |"
                  for r in rows if r["step"]]
            L.append("")
    if c["deposits"]:
        L += ["## Deposits", "", "| Epoch | Check | Yano (ADA) | Koios (ADA) | Yano − Koios (lovelace) |",
              "|---:|---|---:|---:|---:|"]
        L += [f"| {r['epoch']} | {r['check']} | {ada(r['yano'])} | {ada(r['koios'])} | {r['yano'] - r['koios']:+,} |"
              for r in c["deposits"]]
        L.append("")
    if c["legacy_deposits"]:
        L += [f"AdaPots without deposit categories (written before ADR-058, not compared): "
              f"{ranges(c['legacy_deposits'])}", ""]
    da = c["deposit_audit"]
    if da is None:
        L += ["Deposit audit at the tip: `/api/debug/deposits` is not available on this Yano.", ""]
    else:
        a = da["audit"]
        L += [f"Deposit audit at the tip: total_dep {ada(int(a['total_deposited']))} ADA, {a['account_count']:,} stake "
              f"accounts {ada(int(a['account_deposits']))} ADA, {a['drep_count']:,} DReps "
              f"{ada(int(a['drep_deposits']))} ADA, {a['pool_count']:,} pools {ada(int(a['pool_deposits']))} ADA. "
              f"Koios `pool_list` reports {da['koios_pools']:,} registered or retiring pools (informational).", ""]
        L += [f"- FAIL: {f}" for f in da["failures"]]
        if da["failures"]:
            L.append("")
    ds = c["drep_set"]
    for label, ids in (("Registered DReps only in Yano", ds["only_yano"]), ("Registered DReps only in Koios",
                                                                            ds["only_koios"])):
        if ids:
            L += [f"{label} ({len(ids)}, first 50): " + ", ".join(f"`{i}`" for i in ids[:50]), ""]
    if c["drep_rows"]:
        L += ["## DRep distribution totals", "", "| Epoch | Yano (ADA) | Koios (ADA) | Δ (lovelace) | Yano DReps | "
              "Koios DReps |", "|---:|---:|---:|---:|---:|---:|"]
        L += [f"| {r['epoch']} | {ada(r['yano'])} | {ada(r['koios'])} | {r['yano'] - r['koios']:+,} | {r['yano_n']} | "
              f"{r['koios_n']} |" for r in c["drep_rows"]]
        L.append("")
    if c["drep_diff"]:
        d = c["drep_diff"]
        L += [f"Per-DRep differences at epoch {d['epoch']} ({len(d['rows'])}, first 50):", "",
              "| DRep | Yano (lovelace) | Koios (lovelace) |", "|---|---:|---:|"]
        L += [f"| `{i}` | {'absent' if y is None else f'{y:,}'} | {'absent' if k is None else f'{k:,}'} |"
              for i, y, k in d["rows"][:50]]
        L.append("")
    if c["proposals"]:
        L += ["## Governance proposals", "", "| Proposal | Type | Issue |", "|---|---|---|"]
        L += [f"| `{i}` | {t} | {issue} |" for i, t, issue in c["proposals"]]
        L.append("")
    return "\n".join(L)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--network", required=True, choices=sorted(KOIOS_URLS))
    ap.add_argument("--yano", required=True, help="Yano base URL, for example http://localhost:7171")
    ap.add_argument("--koios", help="Koios base URL (…/api/v1); defaults to the public one for --network")
    ap.add_argument("--from-epoch", type=int, default=0)
    ap.add_argument("--to-epoch", type=int, help="default: Koios tip")
    ap.add_argument("--drep-epochs", type=int, default=2, help="DRep distribution epochs to compare (0 = skip)")
    ap.add_argument("--koios-interval", type=float, default=1.0, help="minimum seconds between Koios requests")
    ap.add_argument("--yano-workers", type=int, default=4, help="parallel Yano requests for per-DRep reads")
    ap.add_argument("--timeout", type=float, default=60.0, help="seconds per request")
    ap.add_argument("--cafile", help="CA bundle for Koios TLS, when Python has no usable trust store")
    ap.add_argument("--out", help="Markdown report file (default: stdout)")
    args = ap.parse_args()

    try:
        koios = Koios(args.koios or KOIOS_URLS[args.network], args.koios_interval, args.timeout, args.cafile)
        yano = Yano(args.yano, args.timeout)
        yano_tip = int(yano.get("epochs/latest")["epoch"])
        params = yano.get("epochs/latest/parameters")
        pool_deposit = int(params["pool_deposit"])
        koios_tip = int(koios.get("tip")[0]["epoch_no"])
        first, last = args.from_epoch, koios_tip if args.to_epoch is None else args.to_epoch
        log(f"Yano tip epoch {yano_tip}, Koios tip epoch {koios_tip}, epochs {first}-{last}")

        koios_totals = {int(r["epoch_no"]): r for r in koios.get_all("totals", {
            "select": "epoch_no,treasury,reserves,fees,deposits_stake,deposits_drep,deposits_proposal",
            "and": f"(epoch_no.gte.{first},epoch_no.lte.{last})", "order": "epoch_no.asc"})}
        yano_pots = {}
        for a in range(first, min(last, yano_tip) + 1, 100):
            for row in yano.get("epochs/adapots", {"from": a, "to": min(a + 99, last, yano_tip), "count": 100,
                                                   "page": 1, "order": "asc"}):
                yano_pots[int(row["epoch"])] = row
        first_yano = min(yano_pots, default=yano_tip + 1)
        epochs = sorted(e for e in koios_totals if e in yano_pots)
        gaps = sorted(e for e in koios_totals if first_yano <= e <= yano_tip and e not in yano_pots)
        not_synced = sorted(e for e in koios_totals if e > yano_tip)

        pots = compare_pots(koios_totals, yano_pots, epochs)
        deposits, legacy_deposits = compare_deposits(koios_totals, yano_pots, epochs)
        tip = min(yano_tip, koios_tip)
        audit = deposit_audit(koios, yano, pool_deposit)

        yano_reg = {r["drep_id"] for r in yano.pages("governance/dreps", {"status": "active"})}
        koios_reg = {r["drep_id"] for r in koios.get_all("drep_list", {"select": "drep_id", "registered": "eq.true"})}
        drep_set = {"only_yano": sorted(yano_reg - koios_reg), "only_koios": sorted(koios_reg - yano_reg)}

        koios_props = koios.get_all("proposal_list", {
            "select": "proposal_tx_hash,proposal_index,proposal_type,proposed_epoch,ratified_epoch,enacted_epoch,"
                      "dropped_epoch,expired_epoch,expiration,deposit"})
        proposals, props_checked = compare_proposals(
            koios_props, yano.pages("governance/proposals", {"status": "all"}), yano_tip)

        drep_rows, drep_bad, drep_diff = [], 0, None
        if args.drep_epochs > 0:
            drep_last = min(last, tip)
            drep_rows, drep_bad, drep_diff = compare_drep_distribution(
                koios, yano, max(first, drep_last - args.drep_epochs + 1), drep_last, args.yano_workers)
    except FetchError as e:
        log(f"ERROR: {e}")
        return 2

    set_bad = len(drep_set["only_yano"]) + len(drep_set["only_koios"])
    summary = [
        ("Treasury", len(epochs), len(pots["treasury"])),
        ("Reserves", len(epochs), len(pots["reserves"])),
        ("Fees", len(epochs), len(pots["fees"])),
        ("AdaPot missing in Yano", len(gaps), len(gaps)),
        ("Deposits by category (exact)", len(epochs) - len(legacy_deposits), len({r["epoch"] for r in deposits})),
        ("Deposit audit at the tip", 2 if audit else 0, len(audit["failures"]) if audit else 0),
        ("Registered DReps at the tip", len(koios_reg), set_bad),
        ("DRep distribution totals", len(drep_rows), drep_bad),
        ("Governance proposals", props_checked, len(proposals)),
    ]
    total_bad = sum(bad for _, _, bad in summary)
    report = render({
        "network": args.network, "generated": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "yano_url": args.yano, "koios_url": koios.base, "yano_tip": yano_tip, "koios_tip": koios_tip,
        "epochs": epochs, "not_synced": not_synced, "gaps": gaps, "yano_requests": yano.requests,
        "koios_requests": koios.requests, "summary": summary, "total_bad": total_bad, "pots": pots,
        "deposits": deposits, "legacy_deposits": legacy_deposits, "deposit_audit": audit, "drep_set": drep_set,
        "drep_rows": drep_rows, "drep_diff": drep_diff, "proposals": proposals,
    })
    if args.out:
        with open(args.out, "w") as f:
            f.write(report + "\n")
    else:
        print(report)
    log(f"{'PASS' if total_bad == 0 else 'FAIL'}: {total_bad} mismatching items")
    return 1 if total_bad else 0


if __name__ == "__main__":
    sys.exit(main())
