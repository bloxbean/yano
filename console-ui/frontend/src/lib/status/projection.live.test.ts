import { describe, expect, it } from 'vitest';
import live from './__fixtures__/live-preprod.json';
import {
  count, datasetRows, humanizeDuration, parseArtifactContracts, projectionState, queryableRange
} from './projection';
import type { ArchiveHistoryStatus, ProjectionCoverage, ProjectionWatermark } from '../api/types';

/**
 * The previous History tab passed every unit test and type check while being completely broken
 * against a real node, because nothing ever fed it a real payload. These run the panel's own
 * derivations over responses captured verbatim from a live preprod node, so a backend rename
 * that silently empties the tab fails here instead of in front of an operator.
 */
const status = live.status as ArchiveHistoryStatus;
const coverage = live.coverage as ProjectionCoverage;
const watermark = live.watermark as ProjectionWatermark;

describe('against a live preprod capture', () => {
  it('reports a syncing archive as READY, not as an error', () => {
    // Captured mid fresh sync, thousands of blocks behind tip. That is the designed state of a
    // finality-gated archive and must not read as a fault.
    expect(projectionState(status, coverage)).toBe('READY');
  });

  it('renders the committed range from the real coordinates', () => {
    const range = queryableRange(coverage);

    expect(range).not.toBeNull();
    expect(range).toContain((coverage.queryableThroughBlock as number).toLocaleString());
    expect(count(coverage.tipBlock)).not.toBe('—');
    expect(count(coverage.blocksBehindTip)).not.toBe('—');
  });

  it('joins a projection version onto every block section', () => {
    // The regression this guards: sections, status and watermark spell datasets differently,
    // so a raw join leaves every version blank while the table still renders.
    const rows = datasetRows(status, coverage, watermark);
    const sections = rows.filter((row) => row.kind === 'block section');

    expect(sections.length).toBeGreaterThan(0);
    expect(sections.every((row) => row.version !== null)).toBe(true);
  });

  it('classifies the five shipped epoch artifacts', () => {
    const rows = datasetRows(status, coverage, watermark);
    const artifacts = rows.filter((row) => row.kind === 'epoch artifact').map((row) => row.name);

    expect(artifacts).toEqual(['ada_pot', 'drep_distribution', 'epoch_stake',
      'governance_proposal_status', 'reward']);
  });

  it('decodes every artifact contract the node published', () => {
    const contracts = parseArtifactContracts(coverage.artifactContracts);

    expect(contracts).toHaveLength(5);
    expect(contracts.filter((contract) => contract.reconstructibility === 'IRREPRODUCIBLE')
      .map((contract) => contract.dataset))
      .toEqual(['drep-distribution', 'governance-proposal-status', 'reward']);
  });

  it('humanises the commit latency the node actually reports', () => {
    expect(humanizeDuration(coverage.maxCommitLatency)).toMatch(/^\d+(\.\d+)?[hms]/);
  });

  it('carries the full-scan caveat so the tab can state it', () => {
    expect(coverage.transactionHashLookup?.mode).toBe('full-scan');
    expect(coverage.note).toContain('unknown rather than absent');
  });
});
