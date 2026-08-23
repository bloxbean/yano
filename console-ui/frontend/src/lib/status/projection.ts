import type { ArchiveHistoryStatus, ProjectionCoverage, ProjectionWatermark } from '../api/types';

/**
 * Read helpers for the ADR-039 projection archive.
 *
 * The single rule everything here exists to enforce: the archive reports -1 for "unknown",
 * and unknown is not zero. A block-number sentinel coerced to 0 renders an archive that has
 * committed nothing as one that has committed genesis, which is the opposite of the truth.
 */

export type ProjectionState =
  | 'DISABLED' | 'UNAVAILABLE' | 'UNHEALTHY' | 'AWAITING_GENESIS' | 'CATCHING_UP' | 'READY';

/** The archive's sentinel for "not known yet". */
const UNKNOWN = -1;

/**
 * A block number the archive actually knows, or null.
 *
 * Anything non-finite, absent, or equal to the -1 sentinel is unknown. Callers must render
 * null as an explicit placeholder rather than substituting a number.
 */
export function known(value: unknown): number | null {
  if (value === null || value === undefined) return null;
  // Number('') and Number(false) are both 0, so a blank or boolean field would otherwise
  // arrive as block zero - the exact "unknown rendered as committed genesis" defect.
  if (typeof value === 'boolean') return null;
  if (typeof value === 'string' && value.trim() === '') return null;
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return null;
  return numeric === UNKNOWN ? null : numeric;
}

/** Format a known count, or an em dash when the archive does not know it. */
export function count(value: unknown): string {
  const resolved = known(value);
  return resolved === null ? '—' : resolved.toLocaleString();
}

/**
 * What the History tab should say the archive is doing.
 *
 * Lag behind tip is deliberately not a state. An ADR-039 archive drains only finalized blocks,
 * so trailing the chain tip is its designed behaviour, not a degradation - reporting that as
 * CATCHING_UP would make a healthy archive look permanently unwell. Only the absence of any
 * committed range is genuinely "not ready yet".
 */
export function projectionState(status: ArchiveHistoryStatus | undefined,
                                coverage: ProjectionCoverage | undefined): ProjectionState {
  if (!status?.enabled) return 'DISABLED';
  if (!coverage?.enabled) return 'UNAVAILABLE';
  if (coverage.sinkHealth === 'UNAVAILABLE') return 'UNAVAILABLE';
  if (coverage.sinkHealth === 'DEGRADED') return 'UNHEALTHY';
  // A fresh archive must not claim coverage from block 0 before genesis is durable, or a
  // balance query over a genesis-funded address that never moved answers "nothing".
  if (coverage.genesisCaptured === false) return 'AWAITING_GENESIS';
  if (known(coverage.queryableThroughBlock) === null) return 'CATCHING_UP';
  return 'READY';
}

/** One artifact contract, decoded from its wire form. */
export interface ArtifactContract {
  dataset: string;
  schemaVersion: number;
  codecVersion: number;
  representation: string;
  reconstructibility: string;
}

/**
 * Decode the pipe-separated artifact contract wire form.
 *
 * Each contract is {@code name:s<schema>:c<codec>:REPRESENTATION:RECONSTRUCTIBILITY}. A
 * malformed entry is dropped rather than rendered half-parsed: this table tells an operator
 * which epoch artifacts cannot be rebuilt if lost, so a wrong row is worse than a missing one.
 */
export function parseArtifactContracts(wire: string | undefined): ArtifactContract[] {
  if (!wire) return [];
  return wire.split('|')
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0)
    .map((entry) => entry.split(':'))
    .filter((parts) => parts.length === 5 && parts[1].startsWith('s') && parts[2].startsWith('c'))
    .map((parts) => ({
      dataset: parts[0],
      schemaVersion: Number(parts[1].slice(1)),
      codecVersion: Number(parts[2].slice(1)),
      representation: parts[3],
      reconstructibility: parts[4]
    }))
    .filter((contract) => Number.isFinite(contract.schemaVersion) && Number.isFinite(contract.codecVersion))
    .sort((left, right) => left.dataset.localeCompare(right.dataset));
}

/**
 * Render an ISO-8601 duration as something an operator reads at a glance.
 *
 * Java's Duration.toString() produces PT2S, PT1M30S, PT0.5S. An unrecognised value is returned
 * unchanged rather than dropped, so a shape this does not model is still visible.
 */
export function humanizeDuration(iso: string | undefined): string {
  if (!iso) return '—';
  const match = /^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(iso.trim());
  if (!match || (!match[1] && !match[2] && !match[3])) return iso;
  const parts: string[] = [];
  if (match[1]) parts.push(`${Number(match[1])}h`);
  if (match[2]) parts.push(`${Number(match[2])}m`);
  if (match[3]) parts.push(`${Number(match[3])}s`);
  return parts.join(' ');
}

/**
 * The queryable range as a display string, or null when there is nothing to claim.
 *
 * Both ends must be known: reporting a start with an unknown end would read as an open range
 * extending to tip, which is exactly the claim the archive is refusing to make.
 */
export function queryableRange(coverage: ProjectionCoverage | undefined): string | null {
  const from = known(coverage?.queryableFromBlock);
  const through = known(coverage?.queryableThroughBlock);
  if (from === null || through === null) return null;
  return `${from.toLocaleString()}–${through.toLocaleString()}`;
}

/**
 * Canonical join key for a dataset name.
 *
 * The archive spells one dataset three ways: the section wire name is hyphenated and carries
 * its projection version (account-events:v3), while status and watermark use an underscored
 * singular (account_event). Joining the raw strings silently produces no matches, so every
 * version column renders as unknown while looking perfectly healthy.
 */
export function datasetKey(name: string): string {
  const base = name.trim().toLowerCase().replace(/:v\d+$/, '').replace(/-/g, '_');
  return DATASET_ALIASES[base] ?? base;
}

/** Divergences that normalisation alone cannot reconcile. */
const DATASET_ALIASES: Record<string, string> = {
  account_events: 'account_event'
};

/** One row of the datasets table. */
export interface DatasetRow {
  name: string;
  /** Committed projection version, or null when the watermark does not report one. */
  version: number | null;
  kind: 'block section' | 'epoch artifact';
  /**
   * Versions a row actually has.
   *
   * The watermark reports projection versions for block sections only, so an epoch artifact
   * would otherwise render blank. Its contract carries the versions that do apply to it, and
   * they are a different thing from a projection version - hence a separate field rather than
   * a fallback into one column that would silently mix the two.
   */
  contract: ArtifactContract | null;
}

/**
 * Every dataset this archive holds, block sections and epoch artifacts alike.
 *
 * Status carries the complete list and shares its spelling with the watermark, so the version
 * join is exact. The artifact contracts identify which of them are epoch artifacts - the ones
 * whose durability class matters, because some cannot be rebuilt if lost.
 */
export function datasetRows(status: ArchiveHistoryStatus | undefined,
                            coverage: ProjectionCoverage | undefined,
                            watermark: ProjectionWatermark | undefined): DatasetRow[] {
  const artifacts = new Map(parseArtifactContracts(coverage?.artifactContracts)
    .map((contract) => [datasetKey(contract.dataset), contract]));
  const versions = new Map(Object.entries(watermark?.projectionVersions ?? {})
    .map(([name, version]) => [datasetKey(name), version]));

  return [...new Set(status?.datasets ?? [])]
    .sort((left, right) => left.localeCompare(right))
    .map((name) => {
      const key = datasetKey(name);
      const version = versions.get(key);
      const contract = artifacts.get(key) ?? null;
      return {
        name,
        version: typeof version === 'number' ? version : null,
        kind: contract ? 'epoch artifact' : 'block section',
        contract
      };
    });
}
