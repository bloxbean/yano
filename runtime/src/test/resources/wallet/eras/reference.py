"""Regenerate independent expectations: python3 reference.py (requires cbor2).
Reads raw ledger CBOR fields, never Yaci models or wallet production code.
"""
import hashlib
import json
from pathlib import Path
import cbor2

ROOT = Path(__file__).parent

def untag(value):
    return value.value if isinstance(value, cbor2.CBORTag) else value

def credential(role, cred):
    kind, digest = cred
    assert kind in (0, 1) and len(digest) == 28
    return f"{role}:{'script' if kind else 'key'}:{digest.hex()}"

def address(raw):
    kind = raw[0] >> 4
    if kind <= 3:
        return {credential('payment', (kind & 1, raw[1:29])),
                credential('stake', (kind >> 1, raw[29:57]))}
    if kind <= 7:
        return {credential('payment', (kind & 1, raw[1:29]))}
    if kind in (14, 15):
        return {credential('stake', (kind & 1, raw[1:29]))}
    assert kind == 8  # Byron has no Shelley credentials.
    return set()

def events(tx):
    found = set()
    for reward in tx.get(5, {}):
        found |= address(reward)
    for cert in untag(tx.get(4, [])):
        kind = cert[0]
        if kind in (0, 1, 2, 7, 8, 9, 10, 11, 12, 13):
            found.add(credential('stake', cert[1]))
        elif kind in (16, 17, 18):
            found.add(credential('drep', cert[1]))
        elif kind == 3:  # pool registration: reward account and owner key hashes
            found |= address(cert[6])
            found |= {credential('stake', (0, owner)) for owner in untag(cert[7])}
        elif kind == 6:  # MIR recipients, not transfer between pots
            rewards = cert[1][1]
            if isinstance(rewards, dict):
                found |= {credential('stake', cred) for cred in rewards}
        else:
            assert kind in (4, 5, 14, 15), f"Unreviewed certificate {kind}"
    for proposal in untag(tx.get(20, [])):
        found |= address(proposal[1])
    return found

def summary(credentials):
    return {'count': len(credentials), 'sha256': hashlib.sha256(
        '\n'.join(sorted(credentials)).encode()).hexdigest()}

manifest = []
for path in sorted(ROOT.glob('*.block')):
    wire = bytes.fromhex(path.read_text().strip())
    era, block = cbor2.loads(wire)
    invalid = set(untag(block[4])) if len(block) > 4 else set()
    transactions = []
    for index, tx in enumerate(block[1]):
        outputs = [tx[16]] if index in invalid and 16 in tx else [] if index in invalid else tx[1]
        outputs = set().union(*(address(out[0]) for out in outputs))
        transactions.append({'outputs': summary(outputs),
                             'events': summary(set() if index in invalid else events(tx))})
    manifest.append({'file': path.name, 'wireSha256': hashlib.sha256(wire).hexdigest(),
                     'era': era, 'transactions': transactions})
(ROOT / 'expected.json').write_text(json.dumps(manifest, indent=2) + '\n')
