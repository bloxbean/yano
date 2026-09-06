"""Compare real filter scans with an independent raw-CBOR historical walk.
Usage: python3 verify.py verification-directory  (requires cbor2)
Reads no Yaci model, wallet matcher, filter implementation, or runtime UTxO data.
"""
import hashlib
import json
import struct
import sys
from pathlib import Path
import cbor2


def head(data, pos):
    initial = data[pos]
    major, small = initial >> 5, initial & 31
    pos += 1
    if small < 24:
        return major, small, pos
    if small == 31:
        return major, None, pos
    assert small <= 27, 'Reserved CBOR additional information'
    size = 1 << (small - 24)
    return major, int.from_bytes(data[pos:pos + size], 'big'), pos + size


def skip(data, pos):
    major, count, cursor = head(data, pos)
    if major in (0, 1, 7):
        return cursor
    if major == 6:
        return skip(data, cursor)
    if major in (2, 3) and count is not None:
        return cursor + count
    if count is None:
        while data[cursor] != 255:
            cursor = skip(data, cursor)
        return cursor + 1
    assert major in (4, 5)
    for _ in range(count * (2 if major == 5 else 1)):
        cursor = skip(data, cursor)
    return cursor


def raw_transactions(data):
    major, count, pos = head(data, 0)
    assert (major, count) == (4, 2)
    _, _, pos = head(data, pos)  # era number
    major, _, pos = head(data, pos)  # block array
    assert major == 4
    pos = skip(data, pos)  # signed header
    major, count, pos = head(data, pos)  # transaction body sequence
    assert major == 4
    index = 0
    while (count is None and data[pos] != 255) or (count is not None and index < count):
        end = skip(data, pos)
        yield data[pos:end]
        pos = end
        index += 1


def untag(value):
    return value.value if isinstance(value, cbor2.CBORTag) else value


def credential(role, value):
    kind, digest = value
    assert kind in (0, 1) and len(digest) == 28
    return (role, 'script' if kind else 'key', digest.hex())


def address(raw):
    kind = raw[0] >> 4
    if kind <= 3:
        return {credential('payment', (kind & 1, raw[1:29])), credential('stake', (kind >> 1, raw[29:57]))}
    if kind <= 7:
        return {credential('payment', (kind & 1, raw[1:29]))}
    if kind in (14, 15):
        return {credential('stake', (kind & 1, raw[1:29]))}
    assert kind == 8
    return set()


def events(tx):
    result = set()
    for reward in tx.get(5, {}):
        result |= address(reward)
    for cert in untag(tx.get(4, [])):
        kind = cert[0]
        if kind in (0, 1, 2, 7, 8, 9, 10, 11, 12, 13):
            result.add(credential('stake', cert[1]))
        elif kind in (16, 17, 18):
            result.add(credential('drep', cert[1]))
        elif kind == 3:
            result |= address(cert[6])
            result |= {credential('stake', (0, owner)) for owner in untag(cert[7])}
        elif kind == 6:
            recipients = cert[1][1]
            if isinstance(recipients, dict):
                result |= {credential('stake', value) for value in recipients}
        else:
            assert kind in (4, 5, 14, 15), f'Unsupported certificate tag {kind}'
    for proposal in untag(tx.get(20, [])):
        result |= address(proposal[1])
    return result


def amount(output):
    value = output[1]
    if isinstance(value, int):
        return value, ''
    coin, policies = value
    assets = sorted(f'{policy.hex()}{name.hex()}={quantity}'
                    for policy, names in policies.items() for name, quantity in names.items())
    return coin, ','.join(assets)


def verify(directory):
    queries = json.loads((directory / 'queries.json').read_text())
    actual = json.loads((directory / 'actual.json').read_text())
    states = []
    for result in actual:
        size = result['credentials']
        query = {(q['role'], q['type'], q['hash']) for q in queries[:size]}
        states.append({'query': query, 'tracked': {}, 'digest': hashlib.sha256(), 'transactions': 0})
    first_seen = {}
    blocks = 0
    with (directory / 'blocks.bin').open('rb') as stream:
        while frame := stream.read(20):
            assert len(frame) == 20, 'Truncated frame header'
            number, slot, length = struct.unpack('>qqI', frame)
            assert number == blocks + 1 and 0 < length < 100_000_000
            raw = stream.read(length)
            assert len(raw) == length, 'Truncated body'
            era, block = cbor2.loads(raw)
            blocks += 1
            if era == 1:  # Byron outputs have no supported Shelley payment/stake credentials.
                continue
            assert 2 <= era <= 7
            assert block[0][0][0] == number and block[0][0][1] == slot
            invalid = set(untag(block[4])) if len(block) > 4 else set()
            raw_bodies = list(raw_transactions(raw))
            assert len(raw_bodies) == len(block[1])
            for index, (tx, raw_body) in enumerate(zip(block[1], raw_bodies)):
                assert cbor2.loads(raw_body) == tx, 'Transaction slice differs from decoded body'
                tx_hash = hashlib.blake2b(raw_body, digest_size=32).hexdigest()
                is_invalid = index in invalid
                inputs = {(h.hex(), i) for h, i in untag(tx.get(13 if is_invalid else 0, []))}
                created = [(len(tx.get(1, [])), tx[16])] if is_invalid and 16 in tx else [] if is_invalid else list(enumerate(tx.get(1, [])))
                annotated = [(out_index, output, address(output[0]), amount(output)) for out_index, output in created]
                event_owners = set() if is_invalid else events(tx)
                for _, output, _, _ in annotated:
                    if output[0][0] >> 4 != 8:
                        first_seen.setdefault(output[0].hex(), slot)
                for state in states:
                    tracked, query = state['tracked'], state['query']
                    matched = any(ref in tracked for ref in inputs) or bool(event_owners & query)
                    for ref in inputs:
                        tracked.pop(ref, None)
                    for out_index, output, owners, value in annotated:
                        if owners & query:
                            matched = True
                            tracked[(tx_hash, out_index)] = value
                    if matched:
                        state['transactions'] += 1
                        state['digest'].update(f'{number}:{slot}:{tx_hash}\n'.encode('ascii'))
            if blocks % 100000 == 0:
                print(f'oracle blocks={blocks}', flush=True)
    compared = []
    for result, state in zip(actual, states):
        lines = sorted(f'{h}#{i}:{coin}:{assets}\n' for (h, i), (coin, assets) in state['tracked'].items())
        expected = {'transactions': state['transactions'], 'transactionDigest': state['digest'].hexdigest(),
                    'unspentOutputs': len(lines), 'outputDigest': hashlib.sha256(''.join(lines).encode('ascii')).hexdigest()}
        for key, value in expected.items():
            assert result[key] == value, f"query={result['credentials']} {key}: actual={result[key]}, oracle={value}"
        compared.append({'credentials': result['credentials'], **expected})
    first_seen_path = directory / 'first-seen.tsv'
    if first_seen_path.exists():
        stored = {}
        for line in first_seen_path.read_text().splitlines():
            key, slot = line.split(':')
            stored[key] = int(slot)
        assert stored == first_seen, 'Exact-address first-seen differs from raw effective outputs'
    report = {'blocks': blocks, 'queries': compared, 'firstSeenCompared': first_seen_path.exists(),
              'shelleyFirstSeenAddresses': len(first_seen), 'result': 'PASS'}
    (directory / 'reference-result.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    verify(Path(sys.argv[1]))
