# ADR-034 Phase 5 Verification Report

Date: 2026-08-14

Phase 5 adds restartable, immutable, paged epoch sources; a bounded epoch
worker; and typed projections for epoch stake, DRep distribution, Ada pots, and
governance proposal observations. Boundary block number/hash/slot/time,
projection/source versions, expiry/dormancy, active state, observation phase,
status code, and structured decision reason are durable columns. Large stake
and DRep sets are staged and read as streams rather than whole-epoch write
batches.

The source manifest and row stream are atomically published, source leases are
durable files, and restart discovery/page continuation are tested. Backend
commit, coverage, retention, and invalidation use the shared contracts already
validated against both engines. Preview exporter removal is completed with
runtime integration after its callbacks are replaced by this durable staging
contract; no new direct export callback is introduced.
