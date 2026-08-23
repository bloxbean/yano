import { describe, expect, it } from 'vitest';
import {
  agoLabel, count, cursorStalled, datasetKey, datasetRows, durationMillis, humanizeDuration,
  known, NO_CURSOR_PROGRESS, observeCursor, parseArtifactContracts, projectionState,
  queryableRange, stalledForMillis
} from './projection';

/**
 * The defect these exist to prevent is silent: the archive reports -1 for "unknown", and every
 * plausible formatting helper turns -1 into 0 or into the literal "-1". Either one tells an
 * operator the archive has committed genesis when it has committed nothing at all.
 */
/** Captured verbatim from a live preprod node on 2026-08-23. */
const SHIPPED_CONTRACTS =
  'ada-pot:s1:c1:ATOMIC_EVIDENCE:RECONSTRUCTIBLE'
  + '|drep-distribution:s1:c1:STAGED_FILE:IRREPRODUCIBLE'
  + '|epoch-stake:s1:c1:IMMUTABLE_GENERATION:RECONSTRUCTIBLE'
  + '|governance-proposal-status:s1:c1:STAGED_FILE:IRREPRODUCIBLE'
  + '|reward:s1:c1:STAGED_FILE:IRREPRODUCIBLE';

/** The nine datasets GET /status reports, verbatim from the same node. */
const LIVE_DATASETS = ['account_event', 'ada_pot', 'address_transaction', 'drep_distribution',
  'epoch_stake', 'governance_proposal_status', 'reward', 'transaction', 'utxo_history'];

/**
 * GET /history/watermark spells these the same way status does.
 *
 * Deliberately distinct values, not the shipped v1: if every version were the same number, a
 * join that matched the wrong dataset would still produce the right answer and the test would
 * prove nothing. The shipped values are asserted against the live capture instead.
 */
const LIVE_VERSIONS = {
  account_event: 3, address_transaction: 4, transaction: 2, utxo_history: 5
};

describe('unknown is not zero', () => {
  it('treats the -1 sentinel as unknown', () => {
    expect(known(-1)).toBeNull();
    expect(count(-1)).toBe('—');
  });

  it('keeps a genuine zero, which is a real block number', () => {
    expect(known(0)).toBe(0);
    expect(count(0)).toBe('0');
  });

  it('treats absent and non-numeric values as unknown rather than zero', () => {
    for (const value of [undefined, null, '', '  ', 'abc', NaN, true, false]) {
      expect(known(value)).toBeNull();
      expect(count(value)).toBe('—');
    }
  });

  it('passes through known values', () => {
    expect(known(5_087_259)).toBe(5_087_259);
    expect(count(5_087_259)).toBe((5_087_259).toLocaleString());
  });
});

describe('queryable range', () => {
  it('renders a range only when both ends are known', () => {
    expect(queryableRange({ queryableFromBlock: 0, queryableThroughBlock: 1_000 }))
      .toBe(`${(0).toLocaleString()}–${(1_000).toLocaleString()}`);
  });

  it('claims nothing when the upper end is unknown', () => {
    // The dangerous rendering: "0–" reads as an open range extending to tip, which is the one
    // claim an archive with no committed range must not make.
    expect(queryableRange({ queryableFromBlock: 0, queryableThroughBlock: -1 })).toBeNull();
    expect(queryableRange({ queryableFromBlock: -1, queryableThroughBlock: -1 })).toBeNull();
    expect(queryableRange(undefined)).toBeNull();
  });
});

describe('projection state', () => {
  const enabled = { enabled: true };
  const ready = { enabled: true, sinkHealth: 'READY', genesisCaptured: true, queryableThroughBlock: 100 };

  it('is DISABLED when the archive is off', () => {
    expect(projectionState({ enabled: false }, ready)).toBe('DISABLED');
    expect(projectionState(undefined, ready)).toBe('DISABLED');
  });

  it('is UNAVAILABLE when coverage is absent or the sink cannot serve', () => {
    expect(projectionState(enabled, undefined)).toBe('UNAVAILABLE');
    expect(projectionState(enabled, { enabled: false })).toBe('UNAVAILABLE');
    expect(projectionState(enabled, { ...ready, sinkHealth: 'UNAVAILABLE' })).toBe('UNAVAILABLE');
  });

  it('is UNAVAILABLE when the archive failed to start, not CATCHING_UP', () => {
    // A failed init reports enabled:true and nothing else. Describing that as "no batch has
    // committed yet" would tell an operator to wait for something that will never happen.
    expect(projectionState(enabled, { enabled: true, error: "unknown projection section 'transaction:v2'" }))
      .toBe('UNAVAILABLE');
  });

  it('does not call a failed archive DISABLED', () => {
    // Both payloads captured verbatim from a node whose archive failed to initialise: /status
    // reports it as neither enabled nor available, while the reason lives on coverage. Testing
    // status first would answer "not enabled on this node" for a node that was configured to
    // run an archive and could not - and would contradict the error the tab shows beneath it.
    const failedStatus = { enabled: false, available: false };
    const failedCoverage = {
      enabled: true,
      error: "unknown projection section 'transaction:v2'; known sections are "
        + '[account-events:v1, address-transaction:v1, transaction:v1, utxo-history:v1]'
    };

    expect(projectionState(failedStatus, failedCoverage)).toBe('UNAVAILABLE');
  });

  it('is UNHEALTHY on a degraded sink', () => {
    expect(projectionState(enabled, { ...ready, sinkHealth: 'DEGRADED' })).toBe('UNHEALTHY');
  });

  it('is AWAITING_GENESIS until the genesis distribution is durable', () => {
    expect(projectionState(enabled, { ...ready, genesisCaptured: false })).toBe('AWAITING_GENESIS');
  });

  it('is CATCHING_UP when nothing has committed yet', () => {
    expect(projectionState(enabled, { ...ready, queryableThroughBlock: -1 })).toBe('CATCHING_UP');
  });

  it('is READY once a committed range exists', () => {
    expect(projectionState(enabled, ready)).toBe('READY');
    expect(projectionState(enabled, { ...ready, queryableThroughBlock: 0 })).toBe('READY');
  });

  it('does not downgrade a healthy archive for trailing the tip', () => {
    // Draining only finalized blocks means the archive trails tip by design. Treating that as
    // CATCHING_UP would leave a correct archive permanently reporting that it is not ready.
    expect(projectionState(enabled, { ...ready, tipBlock: 5_000_000, blocksBehindTip: 4_320 }))
      .toBe('READY');
  });
});

describe('artifact contracts', () => {
  it('decodes the shipped set', () => {
    const contracts = parseArtifactContracts(SHIPPED_CONTRACTS);

    expect(contracts).toHaveLength(5);
    expect(contracts.map((contract) => contract.dataset)).toEqual([
      'ada-pot', 'drep-distribution', 'epoch-stake', 'governance-proposal-status', 'reward'
    ]);
    expect(contracts[4]).toEqual({
      dataset: 'reward', schemaVersion: 1, codecVersion: 1,
      representation: 'STAGED_FILE', reconstructibility: 'IRREPRODUCIBLE'
    });
  });

  it('drops a malformed entry rather than rendering it half-parsed', () => {
    // This table says which artifacts cannot be rebuilt if lost, so a wrong row is worse than
    // an absent one.
    const contracts = parseArtifactContracts('reward:s1:c1:STAGED_FILE:IRREPRODUCIBLE|garbage|a:b:c');

    expect(contracts.map((contract) => contract.dataset)).toEqual(['reward']);
  });

  it('returns nothing for an archive holding no artifacts', () => {
    expect(parseArtifactContracts('')).toEqual([]);
    expect(parseArtifactContracts(undefined)).toEqual([]);
  });
});

describe('commit latency', () => {
  it('reads Java Duration output as elapsed time', () => {
    expect(humanizeDuration('PT2S')).toBe('2s');
    expect(humanizeDuration('PT1M30S')).toBe('1m 30s');
    expect(humanizeDuration('PT0.5S')).toBe('0.5s');
    expect(humanizeDuration('PT2H')).toBe('2h');
  });

  it('shows an unmodelled value rather than hiding it', () => {
    expect(humanizeDuration('P1D')).toBe('P1D');
    expect(humanizeDuration('PT')).toBe('PT');
  });

  it('renders an absent budget as unknown', () => {
    expect(humanizeDuration(undefined)).toBe('—');
  });
});

describe('dataset rows', () => {
  it('joins versions across the archive\'s three spellings of a dataset', () => {
    // The live trap: sections say 'account-events:v3', status and watermark say
    // 'account_event'. Joining the raw strings matches nothing and every version silently
    // renders as unknown while the table looks perfectly healthy.
    const rows = datasetRows({ datasets: LIVE_DATASETS },
      { artifactContracts: SHIPPED_CONTRACTS }, { projectionVersions: LIVE_VERSIONS });

    expect(rows).toHaveLength(9);
    expect(rows.find((row) => row.name === 'account_event')?.version).toBe(3);
    expect(rows.find((row) => row.name === 'address_transaction')?.version).toBe(4);
    expect(rows.find((row) => row.name === 'transaction')?.version).toBe(2);
    expect(rows.find((row) => row.name === 'utxo_history')?.version).toBe(5);
  });

  it('normalises every spelling to one key', () => {
    expect(datasetKey('account-events:v1')).toBe('account_event');
    expect(datasetKey('account_event')).toBe('account_event');
    expect(datasetKey('utxo-history:v1')).toBe('utxo_history');
    expect(datasetKey('ada-pot')).toBe('ada_pot');
    // Trailing 's' is part of the name here, not a plural to strip.
    expect(datasetKey('governance-proposal-status')).toBe('governance_proposal_status');
  });

  it('separates epoch artifacts from block sections', () => {
    const rows = datasetRows({ datasets: LIVE_DATASETS },
      { artifactContracts: SHIPPED_CONTRACTS }, { projectionVersions: LIVE_VERSIONS });
    const kind = (name: string) => rows.find((row) => row.name === name)?.kind;

    expect(kind('reward')).toBe('epoch artifact');
    expect(kind('epoch_stake')).toBe('epoch artifact');
    expect(kind('governance_proposal_status')).toBe('epoch artifact');
    expect(kind('transaction')).toBe('block section');
    expect(kind('utxo_history')).toBe('block section');
  });

  it('reports an unknown version as null rather than zero', () => {
    const rows = datasetRows({ datasets: ['reward'] }, {}, {});

    expect(rows[0].version).toBeNull();
  });

  it('is empty when the node reports no datasets', () => {
    expect(datasetRows(undefined, undefined, undefined)).toEqual([]);
  });
});

describe('cursor liveness', () => {
  const T0 = 1_700_000_000_000;

  it('reads a duration budget the same way it displays one', () => {
    expect(durationMillis('PT15M')).toBe(900_000);
    expect(durationMillis('PT2S')).toBe(2_000);
    expect(durationMillis('PT1M30S')).toBe(90_000);
    expect(durationMillis('P1D')).toBeNull();
    expect(durationMillis(undefined)).toBeNull();
  });

  it('records movement and holds still when the cursor does not move', () => {
    let progress = observeCursor(NO_CURSOR_PROGRESS, 100, T0);
    expect(stalledForMillis(progress)).toBe(0);

    progress = observeCursor(progress, 100, T0 + 30_000);
    expect(stalledForMillis(progress)).toBe(30_000);

    progress = observeCursor(progress, 101, T0 + 40_000);
    expect(stalledForMillis(progress)).toBe(0);
  });

  it('counts a rollback as movement, because the drain is alive either way', () => {
    let progress = observeCursor(NO_CURSOR_PROGRESS, 500, T0);
    progress = observeCursor(progress, 500, T0 + 60_000);
    progress = observeCursor(progress, 480, T0 + 70_000);

    expect(stalledForMillis(progress)).toBe(0);
  });

  it('does not let an unreadable cursor restart the clock', () => {
    // A transient read failure must not reset the timer, or an ongoing stall would be hidden
    // by exactly the instability that tends to accompany it.
    let progress = observeCursor(NO_CURSOR_PROGRESS, 100, T0);
    progress = observeCursor(progress, null, T0 + 60_000);

    expect(stalledForMillis(progress)).toBe(60_000);
    expect(progress.block).toBe(100);
  });

  it('warns only past the budget the node itself reports', () => {
    let progress = observeCursor(NO_CURSOR_PROGRESS, 100, T0);
    progress = observeCursor(progress, 100, T0 + 890_000);

    // A finality-gated archive batching on a 15-minute linger is meant to look motionless.
    expect(cursorStalled(progress, { maxCommitLatency: 'PT15M' })).toBe(false);

    progress = observeCursor(progress, 100, T0 + 901_000);
    expect(cursorStalled(progress, { maxCommitLatency: 'PT15M' })).toBe(true);
  });

  it('never warns when there is no budget to judge against', () => {
    let progress = observeCursor(NO_CURSOR_PROGRESS, 100, T0);
    progress = observeCursor(progress, 100, T0 + 86_400_000);

    expect(cursorStalled(progress, {})).toBe(false);
    expect(cursorStalled(NO_CURSOR_PROGRESS, { maxCommitLatency: 'PT15M' })).toBe(false);
  });

  it('labels elapsed time for a reader', () => {
    expect(agoLabel(12_000)).toBe('12s ago');
    expect(agoLabel(272_000)).toBe('4m 32s ago');
    expect(agoLabel(7_320_000)).toBe('2h 2m ago');
    expect(agoLabel(null)).toBe('—');
  });
});
