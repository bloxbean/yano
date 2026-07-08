#!/usr/bin/env python3
"""
Compare Yano DRep distribution parquet with DBSync reference data.
Run after each epoch boundary to catch expiry/amount mismatches early.

Usage:
    python3 compare_drep_dist.py <epoch>
    python3 compare_drep_dist.py       # auto-detect latest exported epoch
"""
import sys
import os
import duckdb

YANO_DATA = "data"
DBSYNC_PARQUET = "/Users/satya/work/dbsync-parquet/dbsync-mainnet-parquet/drep_distr_from504_with_hash.parquet"

def find_latest_epoch():
    """Find the latest epoch with a drep_dist.parquet export."""
    epochs = []
    for d in os.listdir(YANO_DATA):
        if d.startswith("epoch="):
            epoch = int(d.split("=")[1])
            parquet = os.path.join(YANO_DATA, d, "drep_dist.parquet")
            if os.path.exists(parquet):
                epochs.append(epoch)
    return max(epochs) if epochs else None

def compare(epoch):
    con = duckdb.connect()

    yano_path = f"{YANO_DATA}/epoch={epoch}/drep_dist.parquet"
    if not os.path.exists(yano_path):
        print(f"ERROR: {yano_path} not found")
        return False

    # Check if DBSync has this epoch
    dbsync_count = con.execute(f"""
        SELECT count(*) FROM '{DBSYNC_PARQUET}' WHERE epoch_no = {epoch}
    """).fetchone()[0]
    if dbsync_count == 0:
        print(f"SKIP: DBSync has no data for epoch {epoch}")
        return True

    # Load both
    yano = con.execute(f"""
        SELECT drep_hash, amount as amount_yano, stored_expiry, num_dormant, effective_expiry, active
        FROM '{yano_path}'
    """).fetchdf()

    dbsync = con.execute(f"""
        SELECT drep_hash, amount as amount_dbsync, active_until
        FROM '{DBSYNC_PARQUET}'
        WHERE epoch_no = {epoch} AND drep_hash IS NOT NULL
    """).fetchdf()

    # Join
    merged = yano.merge(dbsync, on='drep_hash', how='outer')

    only_yano = merged[merged['amount_dbsync'].isna()]
    only_dbsync = merged[merged['amount_yano'].isna()]
    both = merged[merged['amount_yano'].notna() & merged['amount_dbsync'].notna()]

    print(f"=== Epoch {epoch} DRep Distribution Comparison ===")
    print(f"Yano: {len(yano)}, DBSync: {len(dbsync)}, In both: {len(both)}, Only Yano: {len(only_yano)}, Only DBSync: {len(only_dbsync)}")

    # Amount comparison
    both_df = both.copy()
    both_df['amount_diff'] = both_df['amount_yano'] - both_df['amount_dbsync']
    amount_match = both_df[both_df['amount_diff'] == 0]
    amount_mismatch = both_df[both_df['amount_diff'] != 0]
    print(f"\nAmount: {len(amount_match)} match, {len(amount_mismatch)} mismatch")

    if len(amount_mismatch) > 0:
        print(f"  Top 5 amount mismatches:")
        for _, row in amount_mismatch.sort_values('amount_diff', key=abs, ascending=False).head(5).iterrows():
            print(f"    {row['drep_hash'][:16]}... yano={row['amount_yano']:.0f}, dbsync={row['amount_dbsync']:.0f}, diff={row['amount_diff']:.0f}")

    # Expiry comparison (active_until = Haskell stored expiry directly)
    both_df['expiry_diff'] = both_df['effective_expiry'] - both_df['active_until']
    expiry_match = both_df[both_df['expiry_diff'] == 0]
    expiry_mismatch = both_df[both_df['expiry_diff'] != 0]
    print(f"\nExpiry: {len(expiry_match)} match, {len(expiry_mismatch)} mismatch (effective_expiry vs active_until)")

    if len(expiry_mismatch) > 0:
        dist = expiry_mismatch['expiry_diff'].value_counts().sort_index()
        print(f"  Diff distribution: {dict(dist)}")
        print(f"  Top 5 expiry mismatches (by stake):")
        for _, row in expiry_mismatch.sort_values('amount_yano', ascending=False).head(5).iterrows():
            print(f"    {row['drep_hash'][:16]}... stake={row['amount_yano']:.0f}, "
                  f"yano_eff={int(row['effective_expiry'])}, dbsync_au={int(row['active_until'])}, diff={int(row['expiry_diff'])}")

    # Summary
    all_ok = len(amount_mismatch) == 0 and len(expiry_mismatch) == 0 and len(only_yano) == 0 and len(only_dbsync) == 0
    if all_ok:
        print(f"\n✓ PASS — Epoch {epoch}: {len(both)} DReps, all amounts and expiries match")
    else:
        print(f"\n⚠ DIFFERENCES — Epoch {epoch}: {len(amount_mismatch)} amount, {len(expiry_mismatch)} expiry mismatches")

    return all_ok

if __name__ == "__main__":
    if len(sys.argv) > 1:
        epoch = int(sys.argv[1])
    else:
        epoch = find_latest_epoch()
        if epoch is None:
            print("No exported epochs found in data/")
            sys.exit(1)
        print(f"Auto-detected latest epoch: {epoch}\n")

    ok = compare(epoch)
    sys.exit(0 if ok else 1)
