ALTER TABLE account_events RENAME TO account_events_old;

CREATE TABLE account_events (
    stake_credential BLOB NOT NULL,
    stake_credential_type TEXT NOT NULL,
    event_type TEXT NOT NULL,
    tx_hash BLOB NOT NULL,
    block_hash BLOB NOT NULL,
    block_number INTEGER NOT NULL,
    slot INTEGER NOT NULL,
    epoch INTEGER NOT NULL,
    block_time INTEGER NOT NULL,
    tx_index INTEGER NOT NULL,
    event_index INTEGER NOT NULL,
    pool_hash BLOB,
    drep_type TEXT,
    drep_credential BLOB,
    amount INTEGER,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    PRIMARY KEY (stake_credential, slot, tx_index, event_index, event_type, tx_hash)
);

INSERT INTO account_events (
    stake_credential, stake_credential_type, event_type, tx_hash, block_hash,
    block_number, slot, epoch, block_time, tx_index, event_index, pool_hash,
    drep_type, drep_credential, amount, archive_job_id
) SELECT
    stake_credential,
    CASE stake_credential_type
        WHEN 'addr_keyhash' THEN 'key'
        WHEN 'scripthash' THEN 'script'
        ELSE stake_credential_type
    END,
    event_type, tx_hash, block_hash,
    block_number, slot, epoch, block_time, tx_index, event_index, pool_hash,
    NULL, drep_credential, amount, archive_job_id
FROM account_events_old;

DROP TABLE account_events_old;
CREATE INDEX account_events_stake_order
    ON account_events(stake_credential, slot, tx_index, event_index, event_type, tx_hash);
CREATE INDEX account_events_tx_hash ON account_events(tx_hash);
CREATE INDEX account_events_archive_job ON account_events(archive_job_id);
