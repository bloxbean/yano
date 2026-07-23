# ADR-017: Fable External-Review Closure

## Status

Placeholder — deferred until the P0/P1 trusted-member pilot posture is closed

The number is local to the `adr/app-layer` series. Root-level ADR-017 is an
unrelated P2P relay roadmap.

## Date

2026-07-17

## Parent and related decisions

- [ADR-014](014-appchain-adr013-external-review-readiness-and-feasibility-fable.md)
  preserves the external review, reviewer corrections, and maintainer
  disposition.
- [ADR-016](016-authenticated-appchain-consensus-profile-and-typed-runtime-limits.md)
  owns the implemented authenticated app-chain consensus profile and typed
  runtime limits required by the P0 response.
- [ADR-015](015-governed-composite-profile-evolution.md) owns governed composite
  profile evolution.
- [App-layer open items](open_item.md) remains the live execution tracker.

## 0. In plain words

The active delivery plan first closes every P0/P1 item needed for a credible
trusted-member developer preview and permissioned pilot. Fable also identified
useful P2/P3 hardening and documentation work that should not be lost or mixed
into those correctness branches.

This placeholder collects that later closure pass. It does not make the
deferred work a release blocker, and it does not mark the external review
closed merely because the P0/P1 subset is complete.

## 1. Activation trigger

Start this ADR's implementation pass after:

1. the remaining external acceptance item `APP-009` is closed (the local
   engineering formerly tracked as APP-001, APP-002, and APP-007 is complete);
2. the P1 gated preset, Kafka production transport, and governed-profile work
   formerly tracked as APP-004, APP-005, and APP-003 remain green on the
   committed acceptance tree;
3. the trusted-member pilot/release posture has a green acceptance run; and
4. the target deployment tier and regulatory/data posture are known.

APP-006 and APP-011 move ahead of this queue automatically if a semi-trusted
member deployment is planned.

## 2. Deferred closure scope

| Tracker item | Review point | Expected disposition |
|---|---|---|
| `REV-002` | S3 unversioned-provider drift classification (D2) | Reclassify operator-actionable drift as `TARGET_CHANGED`; add reconciliation tests. |
| `REV-003` | IPFS detail-byte stability (D5) | Make a successful effect's detail document stable across out-of-band pin-state changes. |
| `REV-004` | Detail-archive operations | Specify and test replication, backup/restore, disk-full handling, and disaster recovery. |
| `REV-005` | Expiry versus outage sizing | Publish connector-specific sizing rules and examples. |
| `REV-006` | Independent demo evidence | Add optional public-L1 verification, qualify anchor-disabled runs, replace synthetic proof flags, and report retry evidence. |
| `REV-009` | Example-module support status (C3) | State packaging, compatibility, and maintenance ownership clearly. |
| `REV-011` | Test-only production fault seam (C7) | Exclude it from production artifacts or prove and document that it is unreachable. |
| `APP-010` | Missing load/soak envelope | Publish reproducible topology, rate, payload, duration, lag, resource, and failure results. |

## 3. Explicitly conditional or long-term items

These findings need an explicit disposition but not necessarily immediate code:

| Tracker item | Trigger / closure rule |
|---|---|
| `APP-008` | Revisit the exact-empty-root composite attachment residual only if a reproducible deployment path exists. |
| `REV-007` | Extract standalone v1 wire specifications and named review ledgers before a stable public protocol release. |
| `REV-008` | Apply one canonical encoding/domain-hash policy to the next wire contract; do not rewrite frozen v1 solely for uniformity. |
| `REV-010` | Split `AppChainSubsystem` and `EffectRuntime` when feature work next touches those seams or maintenance evidence justifies it. |
| `REV-012` | Require an encrypt-before-staging profile and erasure guidance before any regulated-personal-data claim. |

## 4. Non-goals

- Reopening withdrawn findings about Kafka crash-duplicate coverage or the
  release-closure commit.
- Re-architecting the effect or plugin system.
- Treating receipts as independently verified external truth.
- Pulling demand-driven connector operations or unrelated plugin-v2 work into
  the Fable response.

## 5. Final closure artifact

The final pass must append a dated matrix to ADR-014 with one terminal
disposition for every original and follow-up finding:

- `Fixed`, with commit/test/evidence links;
- `Withdrawn`, with the correcting repository evidence;
- `Accepted limitation`, with deployment and documentation boundary; or
- `Deferred`, with an explicit trigger and owning tracker/ADR.

ADR-017 can move out of Placeholder only when that matrix has an owner, target
release, and acceptance run. It is complete only when no Fable finding is left
without one of those four dispositions.
