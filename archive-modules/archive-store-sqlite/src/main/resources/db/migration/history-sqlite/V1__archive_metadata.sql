CREATE TABLE archive_identity (
    singleton INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
    archive_id TEXT NOT NULL,
    engine TEXT NOT NULL CHECK (engine = 'sqlite'),
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    network_magic INTEGER NOT NULL,
    genesis_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE archive_schema (
    dataset TEXT NOT NULL PRIMARY KEY,
    projection_version INTEGER NOT NULL CHECK (projection_version > 0),
    installed_at INTEGER NOT NULL
);

CREATE TABLE archive_generation (
    singleton INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
    generation INTEGER NOT NULL CHECK (generation >= 0)
);
INSERT INTO archive_generation(singleton, generation) VALUES (1, 0);

CREATE TABLE archive_commits (
    job_id TEXT NOT NULL PRIMARY KEY,
    dataset TEXT NOT NULL,
    projection_version INTEGER NOT NULL,
    source_kind TEXT NOT NULL CHECK (source_kind IN ('BLOCK', 'EPOCH')),
    range_start INTEGER NOT NULL,
    range_end INTEGER NOT NULL CHECK (range_end >= range_start),
    start_slot INTEGER NOT NULL,
    start_hash BLOB NOT NULL,
    end_slot INTEGER NOT NULL,
    end_hash BLOB NOT NULL,
    source_state_version TEXT NOT NULL,
    backend_generation INTEGER NOT NULL,
    ordered_digest TEXT NOT NULL,
    committed_at INTEGER NOT NULL
);

CREATE TABLE archive_commit_counts (
    job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    table_name TEXT NOT NULL,
    row_count INTEGER NOT NULL CHECK (row_count >= 0),
    PRIMARY KEY (job_id, table_name)
);

CREATE TABLE archive_coverage (
    job_id TEXT NOT NULL PRIMARY KEY REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    dataset TEXT NOT NULL,
    projection_version INTEGER NOT NULL,
    source_kind TEXT NOT NULL CHECK (source_kind IN ('BLOCK', 'EPOCH')),
    range_start INTEGER NOT NULL,
    range_end INTEGER NOT NULL CHECK (range_end >= range_start),
    backend_generation INTEGER NOT NULL
);
CREATE INDEX archive_coverage_dataset_range
    ON archive_coverage(dataset, range_start, range_end);

CREATE TABLE archive_invalidations (
    invalidation_id TEXT NOT NULL PRIMARY KEY,
    dataset TEXT NOT NULL,
    source_kind TEXT NOT NULL CHECK (source_kind IN ('BLOCK', 'EPOCH')),
    range_start INTEGER NOT NULL,
    range_end INTEGER NOT NULL CHECK (range_end >= range_start),
    invalidated_at INTEGER NOT NULL
);
CREATE INDEX archive_invalidations_dataset_range
    ON archive_invalidations(dataset, range_start, range_end);
