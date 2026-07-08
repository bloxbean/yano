#!/usr/bin/env python3
"""Verify Yano preprod parquet exports against DBSync preprod database."""

import duckdb
import psycopg2

PARQUET_DIR = "data"
DBSYNC = dict(host="localhost", port=5434, user="postgres", password="postgres", dbname="cexplorer")


def section(title):
    print(f"\n{'='*80}")
    print(f"  {title}")
    print(f"{'='*80}")


# ── 1. ADA POTS ──────────────────────────────────────────────────────────────

def verify_adapots(duck, pg):
    section("ADA POTS — treasury & reserves (epochs 5-282)")

    # Yano
    yano = {}
    for row in duck.execute(f"""
        SELECT epoch, treasury, reserves
        FROM '{PARQUET_DIR}/epoch=*/adapot.parquet'
        ORDER BY epoch
    """).fetchall():
        yano[int(row[0])] = (int(row[1]), int(row[2]))

    # DBSync
    cur = pg.cursor()
    cur.execute("SELECT epoch_no, treasury, reserves FROM ada_pots ORDER BY epoch_no")
    dbsync = {}
    for r in cur.fetchall():
        dbsync[int(r[0])] = (int(r[1]), int(r[2]))

    all_epochs = sorted(set(yano.keys()) & set(dbsync.keys()))
    mismatches = []
    for e in all_epochs:
        yt, yr = yano[e]
        dt, dr = dbsync[e]
        if yt != dt or yr != dr:
            mismatches.append((e, yt, yr, dt, dr))

    yano_only = sorted(set(yano.keys()) - set(dbsync.keys()))
    dbsync_only = sorted(set(dbsync.keys()) - set(yano.keys()))

    print(f"  Yano epochs:   {min(yano.keys())}-{max(yano.keys())} ({len(yano)} total)")
    print(f"  DBSync epochs: {min(dbsync.keys())}-{max(dbsync.keys())} ({len(dbsync)} total)")
    print(f"  Common epochs: {len(all_epochs)}")

    if yano_only:
        print(f"  Yano-only epochs: {yano_only}")
    if dbsync_only:
        print(f"  DBSync-only epochs: {dbsync_only}")

    if not mismatches:
        print(f"\n  RESULT: PASS — all {len(all_epochs)} epochs match on treasury & reserves")
    else:
        print(f"\n  RESULT: FAIL — {len(mismatches)} epoch(s) differ\n")
        print(f"  {'Epoch':>6}  {'Yano Treasury':>22}  {'DBSync Treasury':>22}  {'Treas Diff':>14}  {'Yano Reserves':>22}  {'DBSync Reserves':>22}  {'Res Diff':>14}")
        print(f"  {'-'*6}  {'-'*22}  {'-'*22}  {'-'*14}  {'-'*22}  {'-'*22}  {'-'*14}")
        for e, yt, yr, dt, dr in mismatches:
            print(f"  {e:>6}  {yt:>22}  {dt:>22}  {yt-dt:>14}  {yr:>22}  {dr:>22}  {yr-dr:>14}")


# ── 2. DREP DISTRIBUTION ─────────────────────────────────────────────────────

def verify_drep_dist(duck, pg):
    section("DREP DISTRIBUTION (epochs 164-282)")

    # Yano: per-epoch, per-drep amounts
    yano_data = {}  # {epoch: {drep_hash: amount}}
    for row in duck.execute(f"""
        SELECT epoch, drep_type, drep_hash, amount
        FROM '{PARQUET_DIR}/epoch=*/drep_dist.parquet'
        ORDER BY epoch, drep_hash
    """).fetchall():
        ep = int(row[0])
        dtype = int(row[1])
        dhash = row[2]
        amt = int(row[3])
        # Normalize virtual DRep hashes
        if dtype == 2:
            dhash = "__ABSTAIN__"
        elif dtype == 3:
            dhash = "__NO_CONFIDENCE__"
        yano_data.setdefault(ep, {})[dhash] = amt

    # DBSync: join drep_distr with drep_hash
    cur = pg.cursor()
    cur.execute("""
        SELECT dd.epoch_no,
               COALESCE(encode(dh.raw, 'hex'), dh.view) as drep_hash,
               dd.amount
        FROM drep_distr dd
        JOIN drep_hash dh ON dd.hash_id = dh.id
        ORDER BY dd.epoch_no
    """)
    dbsync_data = {}  # {epoch: {drep_hash: amount}}
    for r in cur.fetchall():
        ep = int(r[0])
        dhash = r[1]
        amt = int(r[2])
        # Normalize virtual DRep names
        if dhash == "drep_always_abstain":
            dhash = "__ABSTAIN__"
        elif dhash == "drep_always_no_confidence":
            dhash = "__NO_CONFIDENCE__"
        dbsync_data.setdefault(ep, {})[dhash] = amt

    all_epochs = sorted(set(yano_data.keys()) & set(dbsync_data.keys()))
    yano_only_epochs = sorted(set(yano_data.keys()) - set(dbsync_data.keys()))
    dbsync_only_epochs = sorted(set(dbsync_data.keys()) - set(yano_data.keys()))

    print(f"  Yano epochs:   {min(yano_data.keys())}-{max(yano_data.keys())} ({len(yano_data)} total)")
    print(f"  DBSync epochs: {min(dbsync_data.keys())}-{max(dbsync_data.keys())} ({len(dbsync_data)} total)")
    print(f"  Common epochs: {len(all_epochs)}")
    if yano_only_epochs:
        print(f"  Yano-only epochs: {yano_only_epochs}")
    if dbsync_only_epochs:
        print(f"  DBSync-only epochs: {dbsync_only_epochs}")

    # Per-epoch comparison
    count_mismatches = []
    total_mismatches = []
    drep_mismatches = []  # (epoch, drep_hash, yano_amt, dbsync_amt)

    for ep in all_epochs:
        yd = yano_data[ep]
        dd = dbsync_data[ep]

        # Count
        if len(yd) != len(dd):
            count_mismatches.append((ep, len(yd), len(dd)))

        # Total
        yt = sum(yd.values())
        dt = sum(dd.values())
        if yt != dt:
            total_mismatches.append((ep, yt, dt))

        # Per-DRep
        all_dreps = set(yd.keys()) | set(dd.keys())
        for d in all_dreps:
            ya = yd.get(d)
            da = dd.get(d)
            if ya != da:
                drep_mismatches.append((ep, d, ya, da))

    # Report: counts
    print(f"\n  --- DRep Count per Epoch ---")
    if not count_mismatches:
        print(f"  PASS — DRep count matches for all {len(all_epochs)} epochs")
    else:
        print(f"  FAIL — {len(count_mismatches)} epoch(s) differ in DRep count")
        for ep, yc, dc in count_mismatches:
            print(f"    Epoch {ep}: Yano={yc}, DBSync={dc}")

    # Report: totals
    print(f"\n  --- Total DRep Distribution per Epoch ---")
    if not total_mismatches:
        print(f"  PASS — total distribution matches for all {len(all_epochs)} epochs")
    else:
        print(f"  FAIL — {len(total_mismatches)} epoch(s) differ in total distribution")
        for ep, yt, dt in total_mismatches:
            print(f"    Epoch {ep}: Yano={yt}, DBSync={dt}, diff={yt-dt}")

    # Report: per-DRep
    print(f"\n  --- Per-DRep Amount ---")
    if not drep_mismatches:
        print(f"  PASS — all per-DRep amounts match across {len(all_epochs)} epochs")
    else:
        print(f"  FAIL — {len(drep_mismatches)} DRep mismatch(es)")
        # Group by epoch for readability
        from collections import defaultdict
        by_epoch = defaultdict(list)
        for ep, d, ya, da in drep_mismatches:
            by_epoch[ep].append((d, ya, da))
        for ep in sorted(by_epoch.keys()):
            items = by_epoch[ep]
            print(f"\n    Epoch {ep} ({len(items)} mismatches):")
            for d, ya, da in items[:10]:  # cap at 10 per epoch
                label = d[:20] + "..." if len(d) > 20 else d
                print(f"      {label:25s}  Yano={ya}  DBSync={da}  diff={( ya or 0)-(da or 0)}")
            if len(items) > 10:
                print(f"      ... and {len(items)-10} more")


# ── 3. PROPOSAL STATUS ───────────────────────────────────────────────────────

def verify_proposals(duck, pg):
    section("PROPOSAL STATUS (epochs 164-282)")

    # Yano: per-epoch snapshot of proposal statuses
    yano_status = {}  # {epoch: {status: count}}
    for row in duck.execute(f"""
        SELECT epoch, status, count(*) as cnt
        FROM '{PARQUET_DIR}/epoch=*/proposal_status.parquet'
        GROUP BY epoch, status
        ORDER BY epoch, status
    """).fetchall():
        ep = int(row[0])
        status = row[1]
        cnt = int(row[2])
        yano_status.setdefault(ep, {})[status] = cnt

    # DBSync: derive per-epoch status counts
    # Ratified at epoch N: ratified_epoch = N
    # Expired at epoch N: expired_epoch = N
    # Dropped at epoch N: dropped_epoch = N
    # Enacted at epoch N: enacted_epoch = N
    # Active at epoch N: submitted before N and not yet ratified/expired/dropped/enacted by N
    cur = pg.cursor()

    # Get submission epoch for each proposal
    cur.execute("""
        SELECT gap.id,
               encode(t.hash, 'hex') as tx_hash,
               gap.index,
               gap.type::text,
               b.epoch_no as submitted_epoch,
               gap.ratified_epoch,
               gap.enacted_epoch,
               gap.expired_epoch,
               gap.dropped_epoch,
               gap.expiration
        FROM gov_action_proposal gap
        JOIN tx t ON gap.tx_id = t.id
        JOIN block b ON t.block_id = b.id
        ORDER BY gap.id
    """)
    proposals = cur.fetchall()

    # Build per-epoch status from DBSync
    dbsync_status = {}  # {epoch: {status: count}}

    # Determine the epoch range from Yano
    yano_epochs = sorted(yano_status.keys())
    if not yano_epochs:
        print("  No proposal data in Yano")
        return

    for ep in yano_epochs:
        counts = {"RATIFIED": 0, "EXPIRED": 0, "ACTIVE": 0}
        for p in proposals:
            pid, tx_hash, idx, ptype, sub_ep, rat_ep, enact_ep, exp_ep, drop_ep, expiration = p
            # Was this proposal visible at this epoch? (submitted before or at this epoch)
            if sub_ep is None or sub_ep > ep:
                continue

            # Determine status at this epoch
            # A proposal that was ratified at epoch R is RATIFIED at epoch R
            # A proposal that expired at epoch E is EXPIRED at epoch E
            # A proposal that was dropped at epoch D is no longer active at D
            # A proposal enacted at epoch N — enacted happens after ratification
            #   typically at the next boundary

            if rat_ep is not None and rat_ep == ep:
                counts["RATIFIED"] += 1
            elif exp_ep is not None and exp_ep == ep:
                counts["EXPIRED"] += 1
            elif drop_ep is not None and drop_ep == ep:
                # dropped = removed (sibling/descendant of enacted)
                # Yano might count these as EXPIRED or not show them
                pass  # we'll handle this below
            elif rat_ep is not None and rat_ep < ep:
                continue  # already processed in earlier epoch
            elif exp_ep is not None and exp_ep < ep:
                continue  # already expired
            elif drop_ep is not None and drop_ep < ep:
                continue  # already dropped
            elif enact_ep is not None and enact_ep <= ep:
                continue  # already enacted
            else:
                counts["ACTIVE"] += 1

        dbsync_status[ep] = counts

    # Compare
    print(f"  Yano epochs with proposals: {len(yano_epochs)}")

    # Print comparison table
    print(f"\n  {'Epoch':>6}  {'Y-Ratified':>10}  {'D-Ratified':>10}  {'Y-Expired':>10}  {'D-Expired':>10}  {'Y-Active':>10}  {'D-Active':>10}  {'Match':>6}")
    print(f"  {'-'*6}  {'-'*10}  {'-'*10}  {'-'*10}  {'-'*10}  {'-'*10}  {'-'*10}  {'-'*6}")

    all_match = True
    mismatch_count = 0
    for ep in yano_epochs:
        yr = yano_status[ep].get("RATIFIED", 0)
        ye = yano_status[ep].get("EXPIRED", 0)
        ya = yano_status[ep].get("ACTIVE", 0)

        dr = dbsync_status.get(ep, {}).get("RATIFIED", 0)
        de = dbsync_status.get(ep, {}).get("EXPIRED", 0)
        da = dbsync_status.get(ep, {}).get("ACTIVE", 0)

        match = yr == dr and ye == de and ya == da
        if not match:
            all_match = False
            mismatch_count += 1
            marker = "FAIL"
        else:
            marker = "ok"

        # Only print mismatches or every 20th epoch for summary
        if not match or ep % 20 == 0 or ep == yano_epochs[0] or ep == yano_epochs[-1]:
            print(f"  {ep:>6}  {yr:>10}  {dr:>10}  {ye:>10}  {de:>10}  {ya:>10}  {da:>10}  {marker:>6}")

    if all_match:
        print(f"\n  RESULT: PASS — all {len(yano_epochs)} epochs match on proposal counts")
    else:
        print(f"\n  RESULT: FAIL — {mismatch_count} epoch(s) differ")


# ── MAIN ──────────────────────────────────────────────────────────────────────

def main():
    print("Yano vs DBSync Preprod Verification")
    print(f"Parquet dir: {PARQUET_DIR}")

    duck = duckdb.connect()
    pg = psycopg2.connect(**DBSYNC)

    try:
        verify_adapots(duck, pg)
        verify_drep_dist(duck, pg)
        verify_proposals(duck, pg)
    finally:
        pg.close()
        duck.close()

    print(f"\n{'='*80}")
    print("  VERIFICATION COMPLETE")
    print(f"{'='*80}")


if __name__ == "__main__":
    main()
