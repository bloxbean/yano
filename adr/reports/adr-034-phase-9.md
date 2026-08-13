# ADR-034 Phase 9 Verification Report

Reward history is archive-only: it is streamed from a durable epoch source only
after finality and never enters hot RocksDB or the rollback journal. Stable,
unbounded source IDs replace the old 20-bit phase sequence, eliminating wrap
and overwrite. Reward types are open codes and explicitly cover Conway proposal
deposit refunds and treasury withdrawals. Current withdrawable account reward
balance remains authoritative in core account state and is unchanged.
